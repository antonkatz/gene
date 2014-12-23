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

object Scraper extends App
{
  private val logger = LoggerFactory.getLogger(getClass)

  private val geneReviewsUrl = "http://www.ncbi.nlm.nih.gov/books/NBK1116"

  private val omimMapFileStub = "/NBKid_shortname_OMIM.txt"

  private val omimMapUrl = "ftp://ftp.ncbi.nih.gov/pub/GeneReviews" + omimMapFileStub

  /** Number of lines that are used as the header in the file mapping GeneReviews id to OMIM id. */
  private val headerLines = 1

  private val columnFormat = "(\\b[\\w]+\\b)\\s(\\b[\\w-]+\\b)\\s(\\b[\\d]+\\b)".r

  private val solrCore = "omim"

  private val solrField = "gene_reviews_link"

  private val solrServer = new HttpSolrServer("http://localhost:8983/solr/" + solrCore)

  /** @deprecated*/
  private val solrUpdateUrl = "http://localhost:8983/solr/%s/update?commit=true".format(solrCore)

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

  omimRawMap match {
    case Some(rawMap) =>
      val stubMap = processRawMap(rawMap)
      val urlMap = stubMap.map(convertStubs)
      urlMap.map(updateSolr)
      rawMap.close()
    case _ => logger.error("Was unable to load GeneReviews for use with OMIM.")
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

  private def loadSolrDocById(id: Int): Option[SolrDocument] =
  {
    val query = new SolrQuery("id:%d".format(id))
    Try(solrServer.query(query).getResults) match {
      case r if r.isFailure =>
        logger.error("Failed to retrieve Solr doc with id: %d".format(id))
        None
      case r =>
        val docList = r.get
        if (docList.getNumFound > 1) {
          logger.warn("Found more that one Solr document with id: %d".format(id))
        } else if (docList.getNumFound < 1) {
          logger.error("Could not find a document with id: %d".format(id))
        }
        Try(docList.get(0)).toOption
    }
  }
  private def modifySolrDocs(map: Map[Int, String]): Iterable[SolrDocument] =
  {
    val existingDocs = map.map(row => loadSolrDocById(row._1) -> row._2)
    val modifiedDocs = existingDocs.map(row =>
      row._1.map(doc => {
        doc.setField(solrField, row._2)
        doc
      })
    ).flatten
    logger.info("Attempted to modify %d Solr documents. Have modified %d.".format(existingDocs.size, modifiedDocs.size))
    modifiedDocs
  }
  private def updateSolr(map: Map[Int, String]) = {
    val modified = modifySolrDocs(map)
    new SolrInputDocument()
    modified.foreach(doc => solrServer.add(ClientUtils.toSolrInputDocument(doc)))
    solrServer.commit()
  }

  /**
   * @deprecated
   */
  private def toSolrJson(map: Map[Int, String]): JsArray =
  {
    map.foldLeft(Json.arr())((json, mapEntry) => {
      val entryJson = Json.obj("id" -> mapEntry._1, "gene_reviews_link" -> Json.obj("set" -> mapEntry._2))
      json.append(entryJson)
    })
  }

  /**
   * @deprecated
   */
  private def commitToSolr(solrDocs: JsArray) =
  {
    val updateUrl = url(solrUpdateUrl)
      .addHeader("Content-type", "application/json") << Json.stringify(solrDocs)
    val r = Http(updateUrl.POST)

    r.onComplete {
      case resp if resp.isFailure =>
        logger.error("Solr update request failed. Cause %s".format(resp.failed.get.getMessage))
      case resp if resp.isSuccess =>
        val response = resp.get
        logger.info("Solr server returned status %d upon commit.".format(response.getStatusCode))
        val logResponse = {
          if (response.getStatusCode >= 400) {
            logger.error(_: String)
          } else
          {
            logger.info(_: String)
          }
        }
        logResponse("The response was: %s".format(response.getResponseBody))
    }
  }
}
