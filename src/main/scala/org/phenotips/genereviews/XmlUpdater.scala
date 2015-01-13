package org.phenotips.genereviews

import org.phenotips.genereviews.Scraper._
import org.slf4j.LoggerFactory
import scala.xml._
import scala.xml.transform.{RuleTransformer, RewriteRule}

/**
 * Modifies existing XML file containing Solr docs.
 */
object XmlUpdater extends App
{
  private val logger = LoggerFactory.getLogger(getClass)

  private val xmlPath = "data/generatedOMIMData.xml"

  private val saveTo = "data/omim_data.xml"

  private val xmlSource = io.Source.fromFile(xmlPath).bufferedReader().readLine()

  private val xml = XML.loadFile(xmlPath)

  getOmimMap.map(updateXml)

  private def updateXml(map: Map[Int, String]) =
  {
    val docs = xml \ "doc"
    val modifiedDocs = docs.map((n: Node) => n match {
      case node: Elem if node.label == "doc" =>
        val geneReviews = <field name="gene_reviews_link"></field>
        new Elem(node.prefix, "doc", node.attributes, node.scope, node.minimizeEmpty,
          (node.child ++ geneReviews).toSeq: _*)
      case node => node
    })

    val root = new Elem(xml.prefix, xml.label, xml.attributes, xml.scope, xml.minimizeEmpty, modifiedDocs.toSeq: _*)
    XML.save(saveTo, root)
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
