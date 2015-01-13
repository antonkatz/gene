package org.phenotips.genereviews

import dispatch.{Http, url}
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.impl.HttpSolrServer
import org.apache.solr.client.solrj.util.ClientUtils
import org.apache.solr.common.{SolrInputDocument, SolrDocument}
import org.phenotips.genereviews.Scraper._
import org.slf4j.LoggerFactory
import play.api.libs.json.{Json, JsArray}
import scala.util.Try

import scala.concurrent.ExecutionContext.Implicits.global

/** Does updating of the Solr server through java */
object JavaSolrUpdate extends App
{
  private val logger = LoggerFactory.getLogger(getClass)

  private val solrCore = "omim"

  private val solrField = "gene_reviews_link"

  private val solrServer = new HttpSolrServer("http://localhost:8983/solr/" + solrCore)


  getOmimMap.map(updateSolr)

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
    // fixme. flip the elements for proper modification statistics
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


  /** @deprecated*/
  private val solrUpdateUrl = "http://localhost:8983/solr/%s/update?commit=true".format(solrCore)

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
