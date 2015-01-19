package org.phenotips.genereviews

import java.io.File

import org.phenotips.genereviews.Scraper._
import org.slf4j.LoggerFactory
import scala.util.Try
import scala.xml._
import scala.xml.Utility
import scala.xml.transform.{RuleTransformer, RewriteRule}

/**
 * Modifies existing XML file containing Solr docs.
 */
object XmlUpdater extends App
{
  private val logger = LoggerFactory.getLogger(getClass)

  private val xmlPath = "data/generatedOMIMData.xml"

  private val saveTo = "data/omim_data.xml"
  private val saveBufferTo = "data/omim_data_buffer.xml"
  private lazy val writer = new java.io.PrintWriter(new java.io.File(saveTo))
  private val xmlSource = io.Source.fromFile(xmlPath).bufferedReader().readLine()

  private val xml = XML.loadFile(xmlPath)

  getOmimMap.map(updateXml)

  private def updateXml(map: Map[Int, String]) =
  {
    /* Not very nice, but this is a simple case and we do avoid running out of memory. */
    writer.println("<%s>".format(xml.label))

    val docs = xml \ "doc"
    val writeAttempts = docs.map((n: Node) => n match {
      case node: Elem if node.label == "doc" =>
        // .head is not safe
        val geneReviews = map.get((node \ "field").find(n => (n \ "@name").text == "id").head.text.toInt).map(link =>
          <field name="gene_reviews_link">
            {link}
          </field>)
        new Elem(node.prefix, "doc", node.attributes, node.scope, node.minimizeEmpty,
          (node.child ++ geneReviews).toSeq: _*)
      case node => node
    }).map(node => {
      val toWrite = Utility.serialize(node)
      Try(writer.println(toWrite))
    })

    logger.info("Tried to write to buffer %d nodes. Written %d nodes.".format(writeAttempts.length,
      writeAttempts.filterNot(_.isFailure).length))

    writer.println("</%s>".format(xml.label))

    writer.close()
    logger.info("Successfully updated the existing xml document")
  }
}

class AddGeneReviews extends RewriteRule
{
  override def transform(n: Node): Node = n match {
    case node: Elem if node.label == "doc" =>
      val geneReviews = <field name="gene_reviews_link"></field>
      new Elem(node.prefix, "doc", node.attributes, node.scope, node.minimizeEmpty,
        (node.child ++ geneReviews).toSeq: _*)
    case any => any
  }
}
