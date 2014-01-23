/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2013 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.batch.phases;

import com.google.common.base.Charsets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.CharEncoding;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentMatcher;
import org.mockito.exceptions.verification.junit.ArgumentsAreDifferent;
import org.sonar.api.CoreProperties;
import org.sonar.api.batch.SonarIndex;
import org.sonar.api.config.Settings;
import org.sonar.api.resources.AbstractLanguage;
import org.sonar.api.resources.Java;
import org.sonar.api.resources.JavaFile;
import org.sonar.api.resources.Languages;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.resources.Resource;
import org.sonar.api.scan.filesystem.FileQuery;
import org.sonar.api.scan.filesystem.internal.DefaultInputFile;
import org.sonar.api.scan.filesystem.internal.InputFile;
import org.sonar.api.scan.filesystem.internal.InputFileBuilder;
import org.sonar.batch.index.ResourceKeyMigration;
import org.sonar.batch.scan.filesystem.DefaultModuleFileSystem;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;

import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class FileIndexerTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();
  private File baseDir;
  private DefaultModuleFileSystem fs;
  private SonarIndex sonarIndex;
  private AbstractLanguage cobolLanguage;
  private Project project;
  private Settings settings;

  private String aClaess;
  private String explicacao;

  @Before
  public void prepare() throws IOException {
    baseDir = temp.newFolder();
    fs = mock(DefaultModuleFileSystem.class);
    sonarIndex = mock(SonarIndex.class);
    project = mock(Project.class);
    settings = new Settings();
    cobolLanguage = new AbstractLanguage("cobol") {
      @Override
      public String[] getFileSuffixes() {
        return new String[] {"cbl"};
      }
    };

    aClaess = new String(new byte[] {65, 67, 108, 97, -61, -88, 115, 115, 40, 41}, CharEncoding.UTF_8);
    explicacao = new String(new byte[] {101, 120, 112, 108, 105, 99, 97, -61, -89, -61, -93, 111, 40, 41}, CharEncoding.UTF_8);
  }

  @Test
  public void should_index_java_files() throws IOException {
    when(fs.inputFiles(FileQuery.all())).thenReturn((Iterable) Arrays.asList(
      newInputFile("src/main/java/foo/bar/Foo.java", "", "foo/bar/Foo.java", "java", false),
      newInputFile("src/main/java2/foo/bar/Foo.java", "", "foo/bar/Foo.java", "java", false),
      newInputFile("src/test/java/foo/bar/FooTest.java", "", "foo/bar/FooTest.java", "java", true)));
    when(project.getLanguageKey()).thenReturn(Java.KEY);
    FileIndexer indexer = new FileIndexer(project, fs, new Languages(Java.INSTANCE), sonarIndex, settings, mock(ResourceKeyMigration.class));
    indexer.execute();

    verify(sonarIndex).index(JavaFile.create("src/main/java/foo/bar/Foo.java", "foo/bar/Foo.java", false));
    verify(sonarIndex).index(JavaFile.create("src/main/java2/foo/bar/Foo.java", "foo/bar/Foo.java", false));
    verify(sonarIndex).index(argThat(new ArgumentMatcher<JavaFile>() {
      @Override
      public boolean matches(Object arg0) {
        JavaFile javaFile = (JavaFile) arg0;
        return javaFile.getKey().equals("src/test/java/foo/bar/FooTest.java") && javaFile.getDeprecatedKey().equals("foo.bar.FooTest")
          && javaFile.getPath().equals("src/test/java/foo/bar/FooTest.java")
          && javaFile.getQualifier().equals(Qualifiers.UNIT_TEST_FILE);
      }
    }));
  }

  @Test
  public void should_index_cobol_files() throws IOException {
    when(fs.inputFiles(FileQuery.all())).thenReturn((Iterable) Arrays.asList(
      newInputFile("src/foo/bar/Foo.cbl", "", "foo/bar/Foo.cbl", "cobol", false),
      newInputFile("src2/foo/bar/Foo.cbl", "", "foo/bar/Foo.cbl", "cobol", false),
      newInputFile("src/test/foo/bar/FooTest.cbl", "", "foo/bar/FooTest.cbl", "cobol", true)));
    when(project.getLanguageKey()).thenReturn("cobol");

    FileIndexer indexer = new FileIndexer(project, fs, new Languages(cobolLanguage), sonarIndex, settings, mock(ResourceKeyMigration.class));
    indexer.execute();

    verify(sonarIndex).index(org.sonar.api.resources.File.create("/src/foo/bar/Foo.cbl", "foo/bar/Foo.cbl", cobolLanguage, false));
    verify(sonarIndex).index(org.sonar.api.resources.File.create("/src2/foo/bar/Foo.cbl", "foo/bar/Foo.cbl", cobolLanguage, false));
    verify(sonarIndex).index(org.sonar.api.resources.File.create("/src/test/foo/bar/FooTest.cbl", "foo/bar/FooTest.cbl", cobolLanguage, true));
  }

  @Test
  public void shouldImportSource() throws IOException {
    settings.setProperty(CoreProperties.CORE_IMPORT_SOURCES_PROPERTY, "true");

    when(fs.inputFiles(FileQuery.all())).thenReturn((Iterable) Arrays.asList(
      newInputFile("src/main/java/foo/bar/Foo.java", "sample code", "foo/bar/Foo.java", "java", false)));
    when(project.getLanguageKey()).thenReturn(Java.KEY);
    FileIndexer indexer = new FileIndexer(project, fs, new Languages(Java.INSTANCE), sonarIndex, settings, mock(ResourceKeyMigration.class));
    indexer.execute();

    Resource sonarFile = JavaFile.create("src/main/java/foo/bar/Foo.java", "foo/bar/Foo.java", false);
    verify(sonarIndex).index(sonarFile);
    verify(sonarIndex).setSource(sonarFile, "sample code");
  }

  private DefaultInputFile newInputFile(String path, String content, String sourceRelativePath, String languageKey, boolean unitTest) throws IOException {
    File file = new File(baseDir, path);
    FileUtils.write(file, content);
    return new InputFileBuilder(file, Charsets.UTF_8, path)
      .attribute(InputFile.ATTRIBUTE_SOURCE_RELATIVE_PATH, sourceRelativePath)
      .attribute(InputFile.ATTRIBUTE_LANGUAGE, languageKey)
      .attribute(InputFile.ATTRIBUTE_TYPE, unitTest ? InputFile.TYPE_TEST : InputFile.TYPE_SOURCE)
      .build();
  }

  @Test
  public void should_use_mac_roman_charset_for_reading_source_files() throws Exception {
    String encoding = "MacRoman";
    String testFile = "MacRomanEncoding.java";
    fileEncodingTest(encoding, testFile);
  }

  @Test
  public void should_use_CP1252_charset_for_reading_source_files() throws Exception {
    String encoding = "CP1252";
    String testFile = "CP1252Encoding.java";
    fileEncodingTest(encoding, testFile);
  }

  @Test(expected = ArgumentsAreDifferent.class)
  public void should_fail_with_wrong_charset_for_reading_source_files() throws Exception {
    String encoding = CharEncoding.UTF_8;
    String testFile = "CP1252Encoding.java";
    fileEncodingTest(encoding, testFile);
  }

  @Test
  public void should_remove_byte_order_mark_character() throws Exception {
    settings.setProperty(CoreProperties.CORE_IMPORT_SOURCES_PROPERTY, "true");

    File javaFile1 = new File(baseDir, "src/main/java/foo/bar/Foo.java");
    FileUtils.write(javaFile1, "\uFEFFpublic class Test", Charsets.UTF_8);
    when(fs.inputFiles(FileQuery.all())).thenReturn((Iterable) Arrays.asList(
      new InputFileBuilder(javaFile1, Charset.forName("UTF-8"), "src/main/java/foo/bar/Foo.java")
        .attribute(InputFile.ATTRIBUTE_SOURCE_RELATIVE_PATH, "foo/bar/Foo.java")
        .attribute(InputFile.ATTRIBUTE_LANGUAGE, "java")
        .build()));
    when(project.getLanguageKey()).thenReturn(Java.KEY);
    FileIndexer indexer = new FileIndexer(project, fs, new Languages(Java.INSTANCE), sonarIndex, settings, mock(ResourceKeyMigration.class));
    indexer.execute();

    Resource sonarFile = JavaFile.create("src/main/java/foo/bar/Foo.java", "foo/bar/Foo.java", false);

    verify(sonarIndex).setSource(eq(sonarFile), argThat(new ArgumentMatcher<String>() {
      @Override
      public boolean matches(Object arg0) {
        String source = (String) arg0;
        return !source.contains("\uFEFF");
      }
    }));
  }

  private void fileEncodingTest(String encoding, String testFile) throws Exception {
    settings.setProperty(CoreProperties.CORE_IMPORT_SOURCES_PROPERTY, "true");

    File javaFile1 = new File(baseDir, "src/main/java/foo/bar/Foo.java");
    FileUtils.copyFile(getFile(testFile), javaFile1);
    when(fs.inputFiles(FileQuery.all()))
      .thenReturn(
        (Iterable) Arrays.asList(
          new InputFileBuilder(javaFile1, Charset.forName(encoding), "src/main/java/foo/bar/Foo.java")
            .attribute(InputFile.ATTRIBUTE_SOURCE_RELATIVE_PATH, "foo/bar/Foo.java")
            .attribute(InputFile.ATTRIBUTE_LANGUAGE, "java")
            .build()));
    when(project.getLanguageKey()).thenReturn(Java.KEY);
    FileIndexer indexer = new FileIndexer(project, fs, new Languages(Java.INSTANCE), sonarIndex, settings, mock(ResourceKeyMigration.class));
    indexer.execute();

    Resource sonarFile = JavaFile.create("/src/main/java/foo/bar/Foo.java", "foo/bar/Foo.java", false);

    verify(sonarIndex).setSource(eq(sonarFile), argThat(new ArgumentMatcher<String>() {
      @Override
      public boolean matches(Object arg0) {
        String source = (String) arg0;
        return source.contains(aClaess) && source.contains(explicacao);
      }
    }));
  }

  private File getFile(String testFile) {
    return new File("test-resources/org/sonar/batch/phases/FileIndexerTest/encoding/" + testFile);
  }

}
