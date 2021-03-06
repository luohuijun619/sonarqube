/*
 * SonarQube
 * Copyright (C) 2009-2019 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.platform.db.migration.version.v76;

import org.sonar.db.Database;
import org.sonar.server.platform.db.migration.step.DataChange;
import org.sonar.server.platform.db.migration.step.MassUpdate;

import java.sql.SQLException;

/**
 * Remove the "sonar.onboardingTutorial.showToNewUsers" settings from the PROPERTIES table
 */
public class DeleteUselessOnboardingSetting extends DataChange {

  public DeleteUselessOnboardingSetting(Database db) {
    super(db);
  }

  @Override
  public void execute(Context context) throws SQLException {
    MassUpdate massUpdate = context.prepareMassUpdate().rowPluralName("useless onboarding settings");
    massUpdate.select("SELECT id FROM properties WHERE prop_key=?")
      .setString(1, "sonar.onboardingTutorial.showToNewUsers");
    massUpdate.update("DELETE FROM properties WHERE id=?");
    massUpdate.execute((row, update) -> {
      long propertyId = row.getLong(1);
      update.setLong(1, propertyId);
      return true;
    });
  }

}
