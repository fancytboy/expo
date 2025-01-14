import JsonFile from '@expo/json-file';
import chalk from 'chalk';
import path from 'path';

import { EXPO_DIR } from '../../Constants';
import logger from '../../Logger';
import { Task } from '../../TasksRunner';
import * as Workspace from '../../Workspace';
import { Parcel, TaskArgs } from '../types';

const { green, yellow, cyan } = chalk;

/**
 * Updates versions of packages to be published in other workspace projects depending on them.
 */
export const updateWorkspaceProjects = new Task<TaskArgs>(
  {
    name: 'updateWorkspaceProjects',
    filesToStage: ['**/package.json', 'yarn.lock'],
  },
  async (parcels: Parcel[]) => {
    logger.info('\n📤 Updating workspace projects...');

    const workspaceInfo = await Workspace.getInfoAsync();
    const dependenciesKeys = [
      'dependencies',
      'devDependencies',
      'peerDependencies',
      'optionalDependencies',
    ];

    const parcelsObject = parcels.reduce((acc, parcel) => {
      acc[parcel.pkg.packageName] = parcel;
      return acc;
    }, {});

    await Promise.all(
      Object.entries(workspaceInfo).map(async ([projectName, projectInfo]) => {
        const projectDependencies = [
          ...projectInfo.workspaceDependencies,
          ...projectInfo.mismatchedWorkspaceDependencies,
        ]
          .map((dependencyName) => parcelsObject[dependencyName])
          .filter(Boolean);

        // If this project doesn't depend on any package we're going to publish.
        if (projectDependencies.length === 0) {
          return;
        }

        // Get copy of project's `package.json`.
        const projectPackageJsonPath = path.join(EXPO_DIR, projectInfo.location, 'package.json');
        const projectPackageJson = await JsonFile.readAsync(projectPackageJsonPath);
        const batch = logger.batch();

        batch.log('  ', green(projectName));

        // Iterate through different dependencies types.
        for (const dependenciesKey of dependenciesKeys) {
          const dependenciesObject = projectPackageJson[dependenciesKey];

          if (!dependenciesObject) {
            continue;
          }

          for (const { pkg, state } of projectDependencies) {
            const currentVersionRange = dependenciesObject[pkg.packageName];

            if (!currentVersionRange) {
              continue;
            }

            // Leave tilde and caret as they are, just replace the version.
            const newVersionRange = currentVersionRange.replace(
              /([\^~]?).*/,
              `$1${state.releaseVersion}`
            );
            dependenciesObject[pkg.packageName] = newVersionRange;

            batch.log(
              '    ',
              `Updating ${yellow(`${dependenciesKey}.${pkg.packageName}`)}`,
              `from ${cyan(currentVersionRange)} to ${cyan(newVersionRange)}`
            );
          }
        }

        // Save project's `package.json`.
        await JsonFile.writeAsync(projectPackageJsonPath, projectPackageJson);

        // Flush batched logs.
        batch.flush();
      })
    );
  }
);
