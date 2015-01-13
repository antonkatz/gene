package org.phenotips.genereviews

import dispatch.{Http, url}
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.impl.HttpSolrServer
import org.apache.solr.client.solrj.util.ClientUtils
import org.apache.solr.common.{SolrInputDocument, SolrDocument}
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsArray, Json}
import scala.io.Source
import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global

object Scraper
{
  private val logger = LoggerFactory.getLogger(getClass)

  private val geneReviewsUrl = "http://www.ncbi.nlm.nih.gov/books/NBK1116"

  private val omimMapFileStub = "/NBKid_shortname_OMIM.txt"

  private val omimMapUrl = "ftp://ftp.ncbi.nih.gov/pub/GeneReviews" + omimMapFileStub

  /** Number of lines that are used as the header in the file mapping GeneReviews id to OMIM id. */
  private val headerLines = 1

  private val columnFormat = "(\\b[\\w]+\\b)\\s(\\b[\\w-]+\\b)\\s(\\b[\\d]+\\b)".r

  private val omimRawMap = Try(Option(io.Source.fromURL(omimMapUrl))).toOption.flatten match {
    case Some(onlineMap: Source) if onlineMap.nonEmpty =>
      logger.info("The newest mapping of GeneReview articles to OMIM has been obtained.")
      Some(onlineMap)
    /* Else get the local (possibly outdated) copy */
    case _ =>
      Try(Option(getClass.getResourceAsStream(omimMapFileStub))).toOption.flatten.collect({
        case stream => io.Source.fromInputStream(stream)
      })
  }
  def getOmimMap = omimRawMap match {
    case Some(rawMap) =>
      val stubMap = processRawMap(rawMap)
      val urlMap = stubMap.map(convertStubs)
      rawMap.close()
      urlMap
    case _ => logger.error("Was unable to load GeneReviews for use with OMIM.")
      None
  }

  /**
   * Extracts the mapping between OMIM ids and the corresponding URL stubs.
   * @param rawMap file containing the mappings
   * @return if no error occurs, a mapping of OMIM id to corresponding URL stub.
   */
  private def processRawMap(rawMap: Source): Option[Map[Int, String]] =
  {
    val processedMap = rawMap.getLines().drop(1)
      .map(line => {
      /* We don't really care if we're losing any mappings here. */
      columnFormat.findFirstMatchIn(line) collect {
        case lineSplit => Tuple1(lineSplit)
      }
    }).toArray
    val totalRows = processedMap.length

    if (processedMap.isEmpty) {
      logger.error("Have not been able to extract a single mapping during extraction.")
      None
    } else
    {
      val map = processedMap.flatten
      val processedRows = map.length
      logger.info("%d rows of the raw mapping have been skipped".format(totalRows - processedRows))

      Try(map.map(row => row._1.group(3).toInt -> row._1.group(2)).toMap).toOption
    }
  }

  /**
   * @param map of OMIM ids to partial URLs
   * @return a map of OMIM ids to full URLs
   */
  private def convertStubs(map: Map[Int, String]) = map.map(row => row._1 -> "%s/%s".format(geneReviewsUrl, row._2))
}
