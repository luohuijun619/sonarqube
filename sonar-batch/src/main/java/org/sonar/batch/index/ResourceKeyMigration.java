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
package org.sonar.batch.index;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.database.DatabaseSession;
import org.sonar.api.database.model.ResourceModel;
import org.sonar.api.resources.Directory;
import org.sonar.api.resources.File;
import org.sonar.api.resources.Java;
import org.sonar.api.resources.JavaFile;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.resources.Resource;
import org.sonar.api.resources.Scopes;
import org.sonar.api.scan.filesystem.internal.DefaultInputFile;
import org.sonar.api.scan.filesystem.internal.InputFile;
import org.sonar.api.utils.PathUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ResourceKeyMigration {

  private final Logger logger;
  private final DatabaseSession session;

  private boolean migrationNeeded = false;

  public ResourceKeyMigration(DatabaseSession session) {
    this(session, LoggerFactory.getLogger(ResourceKeyMigration.class));
  }

  @VisibleForTesting
  ResourceKeyMigration(DatabaseSession session, Logger logger) {
    this.session = session;
    this.logger = logger;
  }

  public void checkIfMigrationNeeded(Project rootProject) {
    ResourceModel model = session.getSingleResult(ResourceModel.class, "key", rootProject.getEffectiveKey());
    if (model != null && StringUtils.isBlank(model.getDeprecatedKey())) {
      logger.info("Resources keys of project '" + rootProject.getName() + "' should be migrated");
      this.migrationNeeded = true;
    }
  }

  public void migrateIfNeeded(Project module, Iterable<InputFile> inputFiles) {
    if (migrationNeeded) {
      logger.info("Starting migration of resource keys");
      Map<String, InputFile> deprecatedFileKeyMapper = new HashMap<String, InputFile>();
      Map<String, InputFile> deprecatedTestKeyMapper = new HashMap<String, InputFile>();
      Map<String, String> deprecatedDirectoryKeyMapper = new HashMap<String, String>();
      for (InputFile inputFile : inputFiles) {
        String deprecatedKey = inputFile.attribute(DefaultInputFile.ATTRIBUTE_COMPONENT_DEPRECATED_KEY);
        if (deprecatedKey != null) {
          if (InputFile.TYPE_TEST.equals(inputFile.attribute(InputFile.ATTRIBUTE_TYPE))) {
            deprecatedTestKeyMapper.put(deprecatedKey, inputFile);
          } else {
            deprecatedFileKeyMapper.put(deprecatedKey, inputFile);
          }
        }
      }

      ResourceModel moduleModel = session.getSingleResult(ResourceModel.class, "key", module.getEffectiveKey());
      int moduleId = moduleModel.getId();
      migrateFiles(module, deprecatedFileKeyMapper, deprecatedTestKeyMapper, deprecatedDirectoryKeyMapper, moduleId);
      migrateDirectories(deprecatedDirectoryKeyMapper, moduleId);
      session.commit();
    }
  }

  private void migrateFiles(Project module, Map<String, InputFile> deprecatedFileKeyMapper, Map<String, InputFile> deprecatedTestKeyMapper,
    Map<String, String> deprecatedDirectoryKeyMapper,
    int moduleId) {
    // Find all FIL or CLA resources for this module
    StringBuilder hql = new StringBuilder().append("from ")
      .append(ResourceModel.class.getSimpleName())
      .append(" where enabled = true ")
      .append(" and rootId = :rootId ")
      .append(" and scope = '").append(Scopes.FILE).append("'");
    List<ResourceModel> resources = session.createQuery(hql.toString()).setParameter("rootId", moduleId).getResultList();
    for (ResourceModel resourceModel : resources) {
      String oldEffectiveKey = resourceModel.getKey();
      boolean isTest = Qualifiers.UNIT_TEST_FILE.equals(resourceModel.getQualifier());
      InputFile matchedFile = findInputFile(deprecatedFileKeyMapper, deprecatedTestKeyMapper, oldEffectiveKey, isTest);
      if (matchedFile != null) {
        String newEffectiveKey = matchedFile.attribute(DefaultInputFile.ATTRIBUTE_COMPONENT_KEY);
        // Now compute migration of the parent dir
        String oldKey = StringUtils.substringAfterLast(oldEffectiveKey, ":");
        Resource sonarFile;
        if (Java.KEY.equals(resourceModel.getLanguageKey())) {
          sonarFile = new JavaFile(oldKey);
        } else {
          sonarFile = new File(oldKey);
        }
        String parentOldKey = module.getEffectiveKey() + ":" + sonarFile.getParent().getDeprecatedKey();
        String parentNewKey = module.getEffectiveKey() + ":" + getParentKey(matchedFile);
        if (!deprecatedDirectoryKeyMapper.containsKey(parentOldKey)) {
          deprecatedDirectoryKeyMapper.put(parentOldKey, parentNewKey);
        } else if (!parentNewKey.equals(deprecatedDirectoryKeyMapper.get(parentOldKey))) {
          logger.warn("Directory with key " + parentOldKey + " matches both " + deprecatedDirectoryKeyMapper.get(parentOldKey) + " and "
            + parentNewKey + ". First match is arbitrary chosen.");
        }
        resourceModel.setKey(newEffectiveKey);
        resourceModel.setDeprecatedKey(oldEffectiveKey);
        logger.info("Migrated resource " + oldEffectiveKey + " to " + newEffectiveKey);
      } else {
        logger.warn("Unable to migrate resource " + oldEffectiveKey + ". No match was found.");
      }
    }
  }

  private InputFile findInputFile(Map<String, InputFile> deprecatedFileKeyMapper, Map<String, InputFile> deprecatedTestKeyMapper, String oldEffectiveKey, boolean isTest) {
    if (isTest) {
      return deprecatedTestKeyMapper.get(oldEffectiveKey);
    } else {
      return deprecatedFileKeyMapper.get(oldEffectiveKey);
    }
  }

  private void migrateDirectories(Map<String, String> deprecatedDirectoryKeyMapper, int moduleId) {
    // Find all DIR resources for this module
    StringBuilder hql = new StringBuilder().append("from ")
      .append(ResourceModel.class.getSimpleName())
      .append(" where enabled = true ")
      .append(" and rootId = :rootId ")
      .append(" and qualifier = '").append(Qualifiers.DIRECTORY).append("')");
    List<ResourceModel> resources = session.createQuery(hql.toString()).setParameter("rootId", moduleId).getResultList();
    for (ResourceModel resourceModel : resources) {
      String oldEffectiveKey = resourceModel.getKey();
      if (deprecatedDirectoryKeyMapper.containsKey(oldEffectiveKey)) {
        String newEffectiveKey = deprecatedDirectoryKeyMapper.get(oldEffectiveKey);
        resourceModel.setKey(newEffectiveKey);
        resourceModel.setDeprecatedKey(oldEffectiveKey);
        logger.info("Migrated resource " + oldEffectiveKey + " to " + newEffectiveKey);
      } else {
        logger.warn("Unable to migrate resource " + oldEffectiveKey);
      }
    }
  }

  private String getParentKey(InputFile matchedFile) {
    String filePath = PathUtils.sanitize(matchedFile.path());
    String parentFolderPath;
    if (filePath.contains(Directory.SEPARATOR)) {
      parentFolderPath = StringUtils.substringBeforeLast(filePath, Directory.SEPARATOR);
    } else {
      parentFolderPath = Directory.SEPARATOR;
    }
    return parentFolderPath;
  }
}
