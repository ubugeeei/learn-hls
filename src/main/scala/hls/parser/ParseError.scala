package hls.parser

/** A playlist syntax or semantic error with a one-based source line. */
final case class ParseError(line: Int, message: String):
  override def toString: String = if line > 0 then s"line $line: $message" else message

