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

import java.net.URL

import org.apache.commons.lang3.SystemUtils
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.verify
import org.neo4j.cypher.internal.runtime.CreateTempFileTestSupport
import org.neo4j.cypher.internal.runtime.ResourceManager
import org.neo4j.cypher.internal.runtime.interpreted.CSVResources.DEFAULT_BUFFER_SIZE
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite
import org.neo4j.exceptions.LoadExternalResourceException
import org.neo4j.internal.kernel.api.AutoCloseablePlus
import org.neo4j.io.fs.FileUtils

class CSVResourcesTest extends CypherFunSuite with CreateTempFileTestSupport {

  var resources: CSVResources = _
  var cleaner: ResourceManager = _

  override def beforeEach() {
    cleaner = mock[ResourceManager]
    resources = new CSVResources(cleaner)
  }

  test("should handle strings") {
    // given
    val url = createCSVTempFileURL {
      writer =>
        writer.println("1")
        writer.println("2")
        writer.println("3")
        writer.println("4")
    }

    //when
    val result: List[Array[String]] = resources.getCsvIterator(new URL(url), List.empty, None, legacyCsvQuoteEscaping = false,
      DEFAULT_BUFFER_SIZE).toList

    (result zip List(
      Array[String]("1"),
      Array[String]("2"),
      Array[String]("3"),
      Array[String]("4")
    )).foreach {
      case (r, expected) =>
        r should equal(expected)
    }
  }

  test("should handle with headers") {
    // given
    val url = createCSVTempFileURL {
      writer =>
        writer.println("a,b")
        writer.println("1,2")
        writer.println("3,4")
    }

    //when
    val result = resources.getCsvIterator(new URL(url), List.empty, None, legacyCsvQuoteEscaping = false,
      DEFAULT_BUFFER_SIZE).toList

    //then
    (result zip List(
      Array[String]("a", "b"),
      Array[String]("1", "2"),
      Array[String]("3", "4")
    )).foreach {
      case (r, expected) =>
        r should equal(expected)
    }
  }

  test("should handle with headers even for uneven files") {
    // given
    val url = createCSVTempFileURL {
      writer =>
        writer.println("a,b")
        writer.println("1,2")
        writer.println("3")
    }

    //when
    val result = resources.getCsvIterator(new URL(url), List.empty, None, legacyCsvQuoteEscaping = false,
      DEFAULT_BUFFER_SIZE).toList

    //then
    (result zip List(
      Array[String]("a", "b"),
      Array[String]("1", "2"),
      Array[String]("3")
    )).foreach {
      case (r, expected) =>
        r should equal(expected)
    }
  }

  test("should give a helpful message when asking for headers with empty file") {
    // given
    val url = createCSVTempFileURL(_ => {})

    //when
    val result = resources.getCsvIterator(new URL(url), List.empty, None, legacyCsvQuoteEscaping = false,
      DEFAULT_BUFFER_SIZE).toList

    result should equal(List.empty)
  }

  test("should register a task in the resource manager") {
    // given
    val url = createCSVTempFileURL {
      writer =>
        writer.println("a,b")
        writer.println("1,2")
        writer.println("3,4")
    }

    // when
    resources.getCsvIterator(new URL(url), List.empty, None, legacyCsvQuoteEscaping = false, DEFAULT_BUFFER_SIZE)

    // then
    verify(cleaner).trace(any(classOf[AutoCloseablePlus]))
  }

  test("should accept and use a custom field terminator") {
    // given
    val url = createCSVTempFileURL {
      writer =>
        writer.println("122\tfoo")
        writer.println("23\tbar")
        writer.println("3455\tbaz")
        writer.println("4\tx")
    }

    //when
    val result: List[Array[String]] = resources.getCsvIterator(new URL(url), List.empty, Some("\t"), legacyCsvQuoteEscaping = false,
      DEFAULT_BUFFER_SIZE).toList

    (result zip List(
      Array[String]("122", "foo"),
      Array[String]("23", "bar"),
      Array[String]("3455", "baz"),
      Array[String]("4", "x")
    )).foreach {
      case (r, expected) =>
        r should equal(expected)
    }
  }

  test("should treat the file as UTF-8 encoded") {
    // given
    val url = createCSVTempFileURL {
      writer =>
        writer.println("Malm\u0246")
        writer.println("K\u0248benhavn")
    }

    //when
    val result: List[Array[String]] = resources.getCsvIterator(new URL(url), List.empty, None, legacyCsvQuoteEscaping = false,
      DEFAULT_BUFFER_SIZE).toList

    (result zip List(
      Array[String]("Malm\u0246"),
      Array[String]("K\u0248benhavn")
    )).foreach {
      case (r, expected) =>
        r should equal(expected)
    }
  }

  test("should propagate source description") {
    // given
    val url = createCSVTempFileURL {
      writer =>
        // an illegal value. There's currently no other way to verify source description
        // from a CSV resource (since it returns an iterator) than via an exception
        // that provides it.
        writer.println("\"quoted\" and then some")
    }

    // when
    val e = intercept[IllegalStateException](resources.getCsvIterator(new URL(url), List.empty, None, legacyCsvQuoteEscaping = false,
      DEFAULT_BUFFER_SIZE))

    var path = url.replace("file:", "")
    if (SystemUtils.IS_OS_WINDOWS) {
      // remove first '/' before something like C:
      path = path.substring(1, path.length)
      // fix '/' into '\'
      path = FileUtils.fixSeparatorsInPath(path)
    }
    e.getMessage should include(path)
  }

  test("should handle local missing file") {
    intercept[LoadExternalResourceException](resources.getCsvIterator(
      new URL("file:///this/file/url/probably/doesnt/exist"), List.empty, None, legacyCsvQuoteEscaping = false,
      DEFAULT_BUFFER_SIZE).toList)
    intercept[LoadExternalResourceException](resources.getCsvIterator(
      new URL("http://127.0.0.1/url/probably/doesnt/exist"), List.empty, None, legacyCsvQuoteEscaping = false,
      DEFAULT_BUFFER_SIZE).toList)
  }

  test("should parse multiline fields") {
    // given
    val url = createCSVTempFileURL {
      writer =>
        writer.println("a\tb")
        writer.println("1\t\"Bar\"")
        writer.println("2\t\"Bar\n\nQuux\n\"")
        writer.println("3\t\"Bar\n\nQuux\"")
    }

    //when
    val result: List[Array[String]] = resources.getCsvIterator(new URL(url), List.empty, Some("\t"), legacyCsvQuoteEscaping = false,
      DEFAULT_BUFFER_SIZE).toList

    (result zip List(
      Array[String]("a", "b"),
      Array[String]("1", "Bar"),
      Array[String]("2", "Bar\n\nQuux\n"),
      Array[String]("3", "Bar\n\nQuux")
    )).foreach {
      case (r, expected) =>
        r should equal(expected)
    }
  }
}
