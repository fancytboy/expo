package expo.modules.updates.loader;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import expo.modules.updates.UpdatesConfiguration;
import expo.modules.updates.UpdatesUtils;
import expo.modules.updates.db.UpdatesDatabase;
import expo.modules.updates.db.entity.AssetEntity;
import expo.modules.updates.db.entity.UpdateEntity;
import expo.modules.updates.db.enums.UpdateStatus;
import expo.modules.updates.manifest.ManifestMetadata;
import expo.modules.updates.manifest.UpdateManifest;

public abstract class Loader {

  private static final String TAG = Loader.class.getSimpleName();

  private final Context mContext;
  private final UpdatesConfiguration mConfiguration;
  private final UpdatesDatabase mDatabase;
  private final File mUpdatesDirectory;
  private final LoaderFiles mLoaderFiles;

  private UpdateManifest mUpdateManifest;
  private UpdateEntity mUpdateEntity;
  private LoaderCallback mCallback;
  private int mAssetTotal = 0;
  private ArrayList<AssetEntity> mErroredAssetList = new ArrayList<>();
  private ArrayList<AssetEntity> mSkippedAssetList = new ArrayList<>();
  private ArrayList<AssetEntity> mExistingAssetList = new ArrayList<>();
  private ArrayList<AssetEntity> mFinishedAssetList = new ArrayList<>();

  public interface LoaderCallback {
    void onFailure(Exception e);
    void onSuccess(@Nullable UpdateEntity update);

    /**
     * Called when an asset has either been successfully downloaded or failed to download.
     *
     * @param asset Entity representing the asset that was either just downloaded or failed
     * @param successfulAssetCount The number of assets that have so far been loaded successfully
     *                             (including any that were found to already exist on disk)
     * @param failedAssetCount The number of assets that have so far failed to load
     * @param totalAssetCount The total number of assets that comprise the update
     */
    void onAssetLoaded(AssetEntity asset, int successfulAssetCount, int failedAssetCount, int totalAssetCount);

    /**
     * Called when a manifest has been downloaded. The calling class should determine whether or not
     * the RemoteLoader should continue to download the update described by this manifest, based on
     * (for example) whether or not it already has the update downloaded locally.
     *
     * @param updateManifest Manifest downloaded by Loader
     * @return true if Loader should download the update described in the manifest,
     *         false if not.
     */
    boolean onUpdateManifestLoaded(UpdateManifest updateManifest);
  }

  protected Loader(Context context, UpdatesConfiguration configuration, UpdatesDatabase database, File updatesDirectory, LoaderFiles loaderFiles) {
    mContext = context;
    mConfiguration = configuration;
    mDatabase = database;
    mUpdatesDirectory = updatesDirectory;
    mLoaderFiles = loaderFiles;
  }

  protected abstract void loadManifest(Context context, UpdatesDatabase database, UpdatesConfiguration configuration, FileDownloader.ManifestDownloadCallback callback);

  protected abstract void loadAsset(AssetEntity assetEntity, File updatesDirectory, UpdatesConfiguration configuration, FileDownloader.AssetDownloadCallback callback);

  protected abstract boolean shouldSkipAsset(AssetEntity assetEntity);

  // lifecycle methods for class

  public void start(LoaderCallback callback) {
    if (mCallback != null) {
      callback.onFailure(new Exception("RemoteLoader has already started. Create a new instance in order to load multiple URLs in parallel."));
      return;
    }

    mCallback = callback;

    loadManifest(mContext, mDatabase, mConfiguration, new FileDownloader.ManifestDownloadCallback() {
      @Override
      public void onFailure(String message, Exception e) {
        finishWithError(message, e);
      }

      @Override
      public void onSuccess(UpdateManifest updateManifest) {
        mUpdateManifest = updateManifest;
        if (mCallback.onUpdateManifestLoaded(updateManifest)) {
          processUpdateManifest(updateManifest);
        } else {
          mUpdateEntity = null;
          finishWithSuccess();
        }
      }
    });
  }

  private void reset() {
    mUpdateEntity = null;
    mCallback = null;
    mAssetTotal = 0;
    mErroredAssetList = new ArrayList<>();
    mSkippedAssetList = new ArrayList<>();
    mExistingAssetList = new ArrayList<>();
    mFinishedAssetList = new ArrayList<>();
  }

  private void finishWithSuccess() {
    if (mCallback == null) {
      Log.e(TAG,  this.getClass().getSimpleName() + " tried to finish but it already finished or was never initialized.");
      return;
    }

    ManifestMetadata.saveMetadata(mUpdateManifest, mDatabase, mConfiguration);

    mCallback.onSuccess(mUpdateEntity);
    reset();
  }

  private void finishWithError(String message, Exception e) {
    Log.e(TAG, message, e);

    if (mCallback == null) {
      Log.e(TAG, this.getClass().getSimpleName() + " tried to finish but it already finished or was never initialized.");
      return;
    }

    mCallback.onFailure(e);
    reset();
  }

  // private helper methods

  private void processUpdateManifest(UpdateManifest updateManifest) {
    if (updateManifest.isDevelopmentMode()) {
      // insert into database but don't try to load any assets;
      // the RN runtime will take care of that and we don't want to cache anything
      UpdateEntity updateEntity = updateManifest.getUpdateEntity();
      mDatabase.updateDao().insertUpdate(updateEntity);
      mDatabase.updateDao().markUpdateFinished(updateEntity);
      finishWithSuccess();
      return;
    }

    UpdateEntity newUpdateEntity = updateManifest.getUpdateEntity();
    UpdateEntity existingUpdateEntity = mDatabase.updateDao().loadUpdateWithId(newUpdateEntity.id);

    // if something has gone wrong on the server and we have two updates with the same id
    // but different scope keys, we should try to launch something rather than show a cryptic
    // error to the user.
    if (existingUpdateEntity != null && !existingUpdateEntity.scopeKey.equals(newUpdateEntity.scopeKey)) {
      mDatabase.updateDao().setUpdateScopeKey(existingUpdateEntity, newUpdateEntity.scopeKey);
      Log.e(TAG, "Loaded an update with the same ID but a different scopeKey than one we already have on disk. This is a server error. Overwriting the scopeKey and loading the existing update.");
    }

    if (existingUpdateEntity != null && existingUpdateEntity.status == UpdateStatus.READY) {
      // hooray, we already have this update downloaded and ready to go!
      mUpdateEntity = existingUpdateEntity;
      finishWithSuccess();
    } else {
      if (existingUpdateEntity == null) {
        // no update already exists with this ID, so we need to insert it and download everything.
        mUpdateEntity = newUpdateEntity;
        mDatabase.updateDao().insertUpdate(mUpdateEntity);
      } else {
        // we've already partially downloaded the update, so we should use the existing entity.
        // however, it's not ready, so we should try to download all the assets again.
        mUpdateEntity = existingUpdateEntity;
      }
      downloadAllAssets(updateManifest.getAssetEntityList());
    }
  }

  private enum AssetLoadResult {
    FINISHED,
    ALREADY_EXISTS,
    ERRORED,
    SKIPPED
  }

  private void downloadAllAssets(List<AssetEntity> assetList) {
    mAssetTotal = assetList.size();
    for (AssetEntity assetEntity : assetList) {
      if (shouldSkipAsset(assetEntity)) {
        handleAssetDownloadCompleted(assetEntity, AssetLoadResult.SKIPPED);
        continue;
      }

      AssetEntity matchingDbEntry = mDatabase.assetDao().loadAssetWithKey(assetEntity.key);
      if (matchingDbEntry != null) {
        mDatabase.assetDao().mergeAndUpdateAsset(matchingDbEntry, assetEntity);
        assetEntity = matchingDbEntry;
      }

      // if we already have a local copy of this asset, don't try to download it again!
      if (assetEntity.relativePath != null && mLoaderFiles.fileExists(new File(mUpdatesDirectory, assetEntity.relativePath))) {
        handleAssetDownloadCompleted(assetEntity, AssetLoadResult.ALREADY_EXISTS);
        continue;
      }

      loadAsset(assetEntity, mUpdatesDirectory, mConfiguration, new FileDownloader.AssetDownloadCallback() {
        @Override
        public void onFailure(Exception e, AssetEntity assetEntity) {
          String identifier = assetEntity.hash != null
                  ? "hash " + UpdatesUtils.bytesToHex(assetEntity.hash)
                  : "key " + assetEntity.key;
          Log.e(TAG, "Failed to download asset with " + identifier, e);
          handleAssetDownloadCompleted(assetEntity, AssetLoadResult.ERRORED);
        }

        @Override
        public void onSuccess(AssetEntity assetEntity, boolean isNew) {
          handleAssetDownloadCompleted(assetEntity, isNew ? AssetLoadResult.FINISHED : AssetLoadResult.ALREADY_EXISTS);
        }
      });
    }
  }

  private synchronized void handleAssetDownloadCompleted(AssetEntity assetEntity, AssetLoadResult result) {
    switch (result) {
      case FINISHED:
        mFinishedAssetList.add(assetEntity);
        break;
      case ALREADY_EXISTS:
        mExistingAssetList.add(assetEntity);
        break;
      case ERRORED:
        mErroredAssetList.add(assetEntity);
        break;
      case SKIPPED:
        mSkippedAssetList.add(assetEntity);
        break;
      default:
        throw new AssertionError("Missing implementation for AssetLoadResult value");
    }

    mCallback.onAssetLoaded(assetEntity, mFinishedAssetList.size() + mExistingAssetList.size(), mErroredAssetList.size(), mAssetTotal);

    if (mFinishedAssetList.size() + mErroredAssetList.size() + mExistingAssetList.size() + mSkippedAssetList.size() == mAssetTotal) {
      try {
        for (AssetEntity asset : mExistingAssetList) {
          boolean existingAssetFound = mDatabase.assetDao().addExistingAssetToUpdate(mUpdateEntity, asset, asset.isLaunchAsset);
          if (!existingAssetFound) {
            // the database and filesystem have gotten out of sync
            // do our best to create a new entry for this file even though it already existed on disk
            // TODO: we should probably get rid of this assumption that if an asset exists on disk with the same filename, it's the same asset
            byte[] hash = null;
            try {
              hash = UpdatesUtils.sha256(new File(mUpdatesDirectory, asset.relativePath));
            } catch (Exception e) {
            }
            asset.downloadTime = new Date();
            asset.hash = hash;
            mFinishedAssetList.add(asset);
          }
        }
        mDatabase.assetDao().insertAssets(mFinishedAssetList, mUpdateEntity);
        if (mErroredAssetList.size() == 0) {
          mDatabase.updateDao().markUpdateFinished(mUpdateEntity, mSkippedAssetList.size() != 0);
        }
      } catch (Exception e) {
        finishWithError("Error while adding new update to database", e);
        return;
      }

      if (mErroredAssetList.size() > 0) {
        finishWithError("Failed to load all assets", new Exception("Failed to load all assets"));
      } else {
        finishWithSuccess();
      }
    }
  }
}
