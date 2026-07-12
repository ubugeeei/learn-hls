package hls.parser

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Values available while parsing `EXT-X-DEFINE` declarations.
 *
 * Imported values are deliberately explicit: variables never leak implicitly from a Multivariant
 * Playlist into a referenced Media Playlist. `playlistUri` is the final URI after redirects, as
 * required by
 * [[https://datatracker.ietf.org/doc/html/draft-pantos-hls-rfc8216bis-22#section-4.4.2.3 HLS 2 draft-22 §4.4.2.3]].
 */
final case class VariableContext(
    playlistUri: Option[URI] = None,
    imported: Map[String, String] = Map.empty
)

/**
 * Expands HLS 2 variable references before ordinary tag parsing.
 *
 * Only URI lines, quoted-string attribute values, and hexadecimal-sequence attribute values are
 * eligible. Replacement values are inserted once and are never recursively expanded.
 */
private[parser] object VariableSubstitution:
  final case class Expansion(
      lines: Vector[String],
      definedVariables: Map[String, String],
      importedNames: Set[String]
  )

  private val NamePattern = "[A-Za-z0-9_-]+".r
  private val Reference   = "\\{\\$([A-Za-z0-9_-]+)\\}".r

  def expand(lines: Vector[String], context: VariableContext): Either[ParseError, Expansion] =
    lines.zipWithIndex
      .foldLeft[Either[ParseError, (Map[String, String], Set[String], Vector[String])]](
        Right((Map.empty, Set.empty, Vector.empty))
      ):
        case (result, (line, index)) =>
          result.flatMap: (variables, imports, output) =>
            if line.startsWith("#EXT-X-DEFINE:") then
              define(line.drop(line.indexOf(':') + 1), index + 1, variables, context)
                .map: (updated, rendered, imported) =>
                  (updated, imports ++ imported, output :+ rendered)
            else
              substituteEligible(line, index + 1, variables)
                .map(output :+ _)
                .map: updated =>
                  (variables, imports, updated)
      .map((variables, imports, output) => Expansion(output, variables, imports))

  private def define(
      input: String,
      line: Int,
      variables: Map[String, String],
      context: VariableContext
  ): Either[ParseError, (Map[String, String], String, Option[String])] =
    AttributeList
      .parse(input, line)
      .flatMap: attributes =>
        val selectors = Vector("NAME", "IMPORT", "QUERYPARAM").filter(attributes.contains)
        if selectors.size != 1 then
          Left(ParseError(line, "EXT-X-DEFINE requires exactly one of NAME, IMPORT, QUERYPARAM"))
        else
          val name = attributes(selectors.head)
          for
            _ <- validateName(name, line)
            _ <- Either.cond(
              !variables.contains(name),
              (),
              ParseError(line, s"duplicate variable: $name")
            )
            value <- selectors.head match
              case "NAME" =>
                attributes.get("VALUE").toRight(ParseError(line, "NAME requires VALUE"))
              case "IMPORT" =>
                context.imported.get(name).toRight(ParseError(line, s"undefined import: $name"))
              case "QUERYPARAM" => queryParameter(context.playlistUri, name, line)
            _ <- rejectForbidden(value, line)
          yield (
            variables.updated(name, value),
            s"#EXT-X-DEFINE:$input",
            Option.when(selectors.head == "IMPORT")(name)
          )

  private def substituteEligible(
      line: String,
      number: Int,
      variables: Map[String, String]
  ): Either[ParseError, String] =
    if line.isEmpty || line.startsWith("#") && !line.startsWith("#EXT") then Right(line)
    else if !line.startsWith("#") then substitute(line, number, variables)
    else
      val result = StringBuilder()
      var quoted = false
      var index  = 0
      while index < line.length do
        val character = line(index)
        if character == '"' then quoted = !quoted
        val hexadecimal = !quoted && index >= 2 && line.slice(index - 2, index) == "0x"
        if (quoted || hexadecimal) && line.startsWith("{$", index) then
          val end = line.indexOf('}', index + 2)
          if end < 0 then return Left(ParseError(number, "unterminated variable reference"))
          val reference = line.slice(index, end + 1)
          substitute(reference, number, variables) match
            case Left(error)  => return Left(error)
            case Right(value) => result.append(value); index = end
        else result.append(character)
        index += 1
      Right(result.result())

  private def substitute(
      input: String,
      line: Int,
      variables: Map[String, String]
  ): Either[ParseError, String] =
    Reference
      .findAllMatchIn(input)
      .foldLeft[Either[ParseError, String]](Right(input)):
        case (result, matched) =>
          result.flatMap: current =>
            variables
              .get(matched.group(1))
              .toRight(ParseError(line, s"undefined variable: ${matched.group(1)}"))
              .map(value => current.replace(matched.matched, value))

  private def validateName(name: String, line: Int): Either[ParseError, Unit] = name match
    case NamePattern() => Right(())
    case _             => Left(ParseError(line, s"invalid variable name: $name"))

  private def rejectForbidden(value: String, line: Int): Either[ParseError, Unit] =
    Either.cond(
      !value.exists(character => character == '"' || character == '\n' || character == '\r'),
      (),
      ParseError(line, "variable value contains a character forbidden in quoted strings")
    )

  private def queryParameter(
      uri: Option[URI],
      name: String,
      line: Int
  ): Either[ParseError, String] =
    uri
      .flatMap(current => Option(current.getRawQuery))
      .toVector
      .flatMap(_.split('&'))
      .flatMap: pair =>
        pair.split("=", 2) match
          case Array(key, value) if decode(key) == name => Some(decode(value))
          case _                                        => None
      .headOption
      .toRight(ParseError(line, s"missing query parameter: $name"))

  private def decode(value: String): String =
    URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
