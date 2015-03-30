package doc

import java.net.URI

import org.kiama.output.PrettyPrinter

import scala.collection.immutable

object HtmlPrettyPrinter extends PrettyPrinter {
  override val defaultIndent = 4
  override val defaultWidth = 132

  def aside(d: Doc): Doc =
    angles("aside") <@> indent(d) <@> angles(forwslash <> "aside")

  def ul(d: immutable.Seq[Doc]): Doc =
    angles("ul") <> nest(lsep(d, softbreak)) <@> angles(forwslash <> "ul")

  def li(d: Doc): Doc =
    angles("li") <> d <> angles(forwslash <> "li")
  def li(name: String, d: Doc): Doc =
    angles("li") <> name <@> indent(d) <@> angles(forwslash <> "li")

  def a(name: String, ref: URI): Doc =
    angles("a" <+> "href" <> "=" <> dquotes(ref.toString)) <> name <> angles(forwslash <> "a")
}
