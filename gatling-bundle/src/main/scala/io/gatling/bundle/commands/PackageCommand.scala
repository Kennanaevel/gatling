/*
 * Copyright 2011-2022 GatlingCorp (https://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatling.bundle.commands

import java.io.{ File, FileOutputStream, InputStream }
import java.nio.file.{ FileVisitResult, Files, Path, SimpleFileVisitor }
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.jar.{ Attributes, JarEntry, JarOutputStream, Manifest }
import java.util.zip.{ ZipFile, ZipOutputStream }

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Using

import io.gatling.bundle.commands.CommandHelper._
import io.gatling.bundle.commands.PackageCommand.WriteEntry
import io.gatling.commons.util.Io._

object PackageCommand {
  private type WriteEntry = (String, JarOutputStream => Unit) => Unit
}

class PackageCommand(args: List[String], maxJavaVersion: Int) {

  private[bundle] def run(): File = {
    compile(args, Some(maxJavaVersion))
    println("Creating the package")
    createJar()
  }

  private def createJar(): File = {
    val file = File.createTempFile("package", ".jar")
    file.deleteOnExit()

    Using(new JarOutputStream(new FileOutputStream(file))) { jos =>
      jos.setLevel(ZipOutputStream.STORED)

      val entriesCache = mutable.HashSet.empty[String]
      val writeEntry: WriteEntry = (entryName, writeContent) => {
        val entry = new JarEntry(entryName)
        if (entriesCache.add(entryName)) {
          jos.putNextEntry(entry)
          writeContent(jos)
          jos.closeEntry()
        }
      }

      addManifest(writeEntry)
      addJarEntries(targetTestClassesDirectory, writeEntry)
      addJarEntries(userResourcesDirectory, writeEntry)
      addJarsContents(userLibsDirectory, writeEntry)

      println("Package created")
      file
    }.fold(throw _, identity)
  }

  private def addManifest(writeEntry: WriteEntry): Unit = {
    writeEntry("META-INF/", _ => ())
    writeEntry(
      "META-INF/MANIFEST.MF",
      jos => {
        val manifest = new Manifest
        manifest.getMainAttributes.putValue(Attributes.Name.MANIFEST_VERSION.toString, "1.0")
        manifest.getMainAttributes.putValue(Attributes.Name.SIGNATURE_VERSION.toString, "GatlingCorp")
        manifest.getMainAttributes.putValue("Gatling-Packager", "bundle")
        manifest.getMainAttributes.putValue("Gatling-Version", gatlingVersion)
        manifest.write(jos)
      }
    )
  }

  private def addJarsContents(rootPath: Path, writeEntry: WriteEntry): Unit = {

    def isExcluded(name: String): Boolean =
      name.equalsIgnoreCase("META-INF/LICENSE") ||
        name.equalsIgnoreCase("META-INF/MANIFEST.MF") ||
        name.startsWith("META-INF/versions/") ||
        name.startsWith("META-INF/maven/") ||
        name.endsWith(".SF") ||
        name.endsWith(".DSA") ||
        name.endsWith(".RSA")

    Files.walkFileTree(
      rootPath,
      new SimpleFileVisitor[Path] {
        override def visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult = {
          if (Files.isRegularFile(path) && path.getFileName.toString.toLowerCase(Locale.ENGLISH).endsWith(".jar")) {
            Using(new ZipFile(path.toFile)) { zip =>
              zip
                .entries()
                .asScala
                .filter(entry => !isExcluded(entry.getName))
                .foreach { entry =>
                  writeEntry(
                    entry.getName,
                    jos =>
                      if (!entry.isDirectory) {
                        copyFromInputStream(zip.getInputStream(entry), jos)
                      }
                  )
                }
            }.fold(throw _, _ => ())
          }

          super.visitFile(rootPath, attrs)
        }
      }
    )
  }

  private def addJarEntries(rootPath: Path, writeEntry: WriteEntry): Unit = {
    Files.walkFileTree(
      rootPath,
      new SimpleFileVisitor[Path] {
        override def visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult = {
          writeEntry(
            rootPath.relativize(path).toString,
            jos =>
              if (Files.isRegularFile(path)) {
                copyFromInputStream(Files.newInputStream(path), jos)
              }
          )
          super.visitFile(rootPath, attrs)
        }
      }
    )
  }

  private def copyFromInputStream(inputStream: => InputStream, jos: JarOutputStream): Unit = {
    Using(inputStream)(_.copyTo(jos)).fold(throw _, _ => ())
    jos.flush()
  }
}
