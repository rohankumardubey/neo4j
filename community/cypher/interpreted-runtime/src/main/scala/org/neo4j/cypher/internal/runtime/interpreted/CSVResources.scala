/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.runtime.interpreted

import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import inet.ipaddr.IPAddressString
import org.neo4j.csv.reader.BufferOverflowException
import org.neo4j.csv.reader.CharReadable
import org.neo4j.csv.reader.CharSeekers
import org.neo4j.csv.reader.Configuration
import org.neo4j.csv.reader.Extractors
import org.neo4j.csv.reader.Mark
import org.neo4j.csv.reader.Readables
import org.neo4j.cypher.internal.runtime.ResourceManager
import org.neo4j.cypher.internal.runtime.interpreted.pipes.ExternalCSVResource
import org.neo4j.cypher.internal.runtime.interpreted.pipes.LoadCsvIterator
import org.neo4j.exceptions.CypherExecutionException
import org.neo4j.exceptions.LoadExternalResourceException
import org.neo4j.internal.kernel.api.AutoCloseablePlus
import org.neo4j.internal.kernel.api.DefaultCloseListenable
import sun.net.www.protocol.http.HttpURLConnection
import org.neo4j.kernel.impl.security.WebURLAccessRule
import org.neo4j.values.storable.Value
import org.neo4j.values.storable.Values

import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.collection.mutable.ArrayBuffer

object CSVResources {
  val DEFAULT_FIELD_TERMINATOR: Char = ','
  val DEFAULT_BUFFER_SIZE: Int = 2 * 1024 * 1024
  val DEFAULT_QUOTE_CHAR: Char = '"'

  private def config(legacyCsvQuoteEscaping: Boolean, csvBufferSize: Int) = Configuration.newBuilder()
    .withQuotationCharacter(DEFAULT_QUOTE_CHAR)
    .withBufferSize(csvBufferSize)
    .withMultilineFields(true)
    .withTrimStrings(false)
    .withEmptyQuotedStringsAsNull(true)
    .withLegacyStyleQuoting(legacyCsvQuoteEscaping)
    .build()
}

case class CSVResource(url: URL, resource: AutoCloseable) extends DefaultCloseListenable with AutoCloseablePlus {
  override def closeInternal(): Unit = resource.close()

  // This is not correct, but hopefully the defensive answer. We don't expect this to be called,
  // but splitting isClosed and setCloseListener into different interfaces leads to
  // multiple inheritance problems instead.
  override def isClosed = false
}

class CSVResources(resourceManager: ResourceManager) extends ExternalCSVResource {

  def getCsvIterator(url: URL,
                     ipBlocklist: List[IPAddressString],
                     fieldTerminator: Option[String],
                     legacyCsvQuoteEscaping: Boolean,
                     bufferSize: Int,
                     headers: Boolean = false): LoadCsvIterator = {

    val reader: CharReadable = getReader(url, ipBlocklist)
    val delimiter: Char = fieldTerminator.map(_.charAt(0)).getOrElse(CSVResources.DEFAULT_FIELD_TERMINATOR)
    val seeker = CharSeekers.charSeeker(reader, CSVResources.config(legacyCsvQuoteEscaping, bufferSize), false)
    val extractor = new Extractors(delimiter).string()
    val intDelimiter = delimiter.toInt
    val mark = new Mark

    val resource = CSVResource(url, seeker)
    resourceManager.trace(resource)

    new LoadCsvIterator {
      var lastProcessed = 0L
      var readAll = false

      override protected[this] def closeMore(): Unit = resource.close()

      private def readNextRow: Array[String] = {
        val buffer = new ArrayBuffer[String]

        try {
          while (seeker.seek(mark, intDelimiter)) {
            val success = seeker.tryExtract(mark, extractor)
            buffer += (if (success) extractor.value() else null)
            if (mark.isEndOfLine) return if (buffer.isEmpty) null else buffer.toArray
          }
        } catch {
          //TODO change to error message mentioning `dbms.import.csv.buffer_size` in 4.0
          case e: BufferOverflowException => throw new CypherExecutionException(e.getMessage, e)
        }

        if (buffer.isEmpty) {
          null
        } else {
          buffer.toArray
        }
      }

      var nextRow: Array[String] = readNextRow

      override def innerHasNext: Boolean = nextRow != null

      override def next(): Array[String] = {
        if (!hasNext) Iterator.empty.next()
        val row = nextRow
        nextRow = readNextRow
        lastProcessed += 1
        readAll = !hasNext
        row
      }
    }
  }

  private def getReader(url: URL, ipBlocklist: List[IPAddressString]) = try {
    val reader = if (url.getProtocol == "file") {
      Readables.files(StandardCharsets.UTF_8, Paths.get(url.toURI))
    } else {
      val inputStream = openStream(url, ipBlocklist)
      Readables.wrap(inputStream, url.toString, StandardCharsets.UTF_8, 0 /*length doesn't matter in this context*/)
    }
    reader
  } catch {
    case e: IOException =>
      throw new LoadExternalResourceException(s"Couldn't load the external resource at: $url", e)
  }

  private def openStream(
    url: URL,
    ipBlocklist: List[IPAddressString],
    connectionTimeout: Int = 2000,
    readTimeout: Int = 10 * 60 * 1000
  ): InputStream = {
    if (url.getProtocol.startsWith("http"))
      TheCookieManager.ensureEnabled()

    val con =
      if (ipBlocklist.nonEmpty) {
        WebURLAccessRule.checkUrlIncludingHops(url, ipBlocklist.asJava)
      } else {
        val newCon = url.openConnection()
        newCon.setRequestProperty(
          "User-Agent",
          s"${WebURLAccessRule.LOAD_CSV_USER_AGENT_PREFIX}${WebURLAccessRule.userAgent()}"
        )
        newCon
      }

    con.setConnectTimeout(connectionTimeout)
    con.setReadTimeout(readTimeout)

    val stream = con.getInputStream
    con.getContentEncoding match {
      case "gzip" => new GZIPInputStream(stream)
      case "deflate" => new InflaterInputStream(stream)
      case _ => stream
    }
  }
}

object TheCookieManager {
  private lazy val theCookieManager = create

  def ensureEnabled() {
    // Force lazy val to be evaluated
    theCookieManager != null
  }

  private def create = {
    val cookieManager = new CookieManager
    CookieHandler.setDefault(cookieManager)
    cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    cookieManager
  }
}

