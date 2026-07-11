package hls.parser

/** RFC 8216 attribute-list tokenizer. Commas inside quoted strings are data. */
private[parser] object AttributeList:
  def parse(input: String, line: Int): Either[ParseError, Map[String, String]] =
    split(input, line).flatMap: parts =>
      parts.foldLeft[Either[ParseError, Map[String, String]]](Right(Map.empty)):
        case (result, part) =>
          result.flatMap: attributes =>
            part.indexOf('=') match
              case -1 => Left(ParseError(line, s"attribute has no '=': $part"))
              case index =>
                val name = part.take(index)
                val raw = part.drop(index + 1)
                if name.isEmpty then Left(ParseError(line, "empty attribute name"))
                else if attributes.contains(name) then Left(ParseError(line, s"duplicate attribute: $name"))
                else unquote(raw, line).map(value => attributes.updated(name, value))

  private def split(input: String, line: Int): Either[ParseError, Vector[String]] =
    val result = Vector.newBuilder[String]
    val current = StringBuilder()
    var quoted = false
    input.foreach:
      case '"' => quoted = !quoted; current += '"'
      case ',' if !quoted => result += current.result(); current.clear()
      case character => current += character
    if quoted then Left(ParseError(line, "unterminated quoted string"))
    else
      result += current.result()
      Right(result.result())

  private def unquote(value: String, line: Int): Either[ParseError, String] =
    if value.startsWith("\"") then
      Either.cond(value.length >= 2 && value.endsWith("\""), value.slice(1, value.length - 1), ParseError(line, "unterminated quoted string"))
    else Right(value)

