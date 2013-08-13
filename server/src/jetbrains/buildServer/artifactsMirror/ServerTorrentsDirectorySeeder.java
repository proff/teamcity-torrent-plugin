/*
 * Copyright (c) 2000-2012 by JetBrains s.r.o. All Rights Reserved.
 * Use is subject to license terms.
 */
package jetbrains.buildServer.artifactsMirror;

import jetbrains.buildServer.artifactsMirror.seeder.FileLink;
import jetbrains.buildServer.artifactsMirror.seeder.TorrentsDirectorySeeder;
import jetbrains.buildServer.artifactsMirror.torrent.TorrentUtil;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.artifacts.BuildArtifact;
import jetbrains.buildServer.serverSide.artifacts.BuildArtifacts;
import jetbrains.buildServer.serverSide.artifacts.BuildArtifactsViewMode;
import jetbrains.buildServer.util.EventDispatcher;
import jetbrains.buildServer.util.FileUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.InetAddress;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;

/**
 * @author Maxim Podkolzine (maxim.podkolzine@jetbrains.com)
 * @since 8.0
 */
public class ServerTorrentsDirectorySeeder {
  private final TorrentTrackerManager myTorrentTrackerManager;
  private final TorrentsDirectorySeeder myTorrentsDirectorySeeder;
  private volatile int myFileSizeThreshold;

  public ServerTorrentsDirectorySeeder(@NotNull ServerPaths serverPaths,
                                       @NotNull TorrentTrackerManager torrentTrackerManager,
                                       @NotNull EventDispatcher<BuildServerListener> eventDispatcher) {
    myTorrentTrackerManager = torrentTrackerManager;
    File torrentsStorage = new File(serverPaths.getPluginDataDirectory(), "torrents");
    torrentsStorage.mkdirs();
    myTorrentsDirectorySeeder = new TorrentsDirectorySeeder(torrentsStorage);

    eventDispatcher.addListener(new BuildServerAdapter() {
      @Override
      public void buildFinished(SRunningBuild build) {
        announceBuildArtifacts(build);
      }
    });
  }


  public void stopSeeder() {
    if (!myTorrentsDirectorySeeder.isStopped()) {
      myTorrentsDirectorySeeder.stop();
    }
  }

  public void startSeeder(@NotNull String seederAddress) {
    stopSeeder();

    try {
      myTorrentsDirectorySeeder.start(InetAddress.getByName(seederAddress));
    } catch (Exception e) {
      Loggers.SERVER.warn("Failed to start torrent seeder, error: " + e.toString());
    }
  }

  public void setFileSizeThreshold(int fileSizeThreshold) {
    myFileSizeThreshold = fileSizeThreshold;
  }

  @NotNull
  public File getTorrentFilesBaseDir(@NotNull SBuild build) {
    final File artifactsDirectory = build.getArtifactsDirectory();
    return new File(artifactsDirectory, TorrentsDirectorySeeder.TORRENTS_DIT_PATH);
  }

  @NotNull
  public Collection<File> getTorrentFiles(@NotNull SBuild build) {
    File baseDir = getTorrentFilesBaseDir(build);
    try {
      return FileUtil.findFiles(new FileFilter() {
        public boolean accept(File file) {
          return file.getName().endsWith(TorrentUtil.TORRENT_FILE_SUFFIX);
        }
      }, baseDir);
    } catch (Exception e) {
      return Collections.emptyList();
    }
  }

  @NotNull
  public File getTorrentFile(@NotNull SBuild build, @NotNull String torrentPath) {
    return new File(getTorrentFilesBaseDir(build), torrentPath);
  }

  public int getNumberOfSeededTorrents() {
    return myTorrentsDirectorySeeder.getNumberOfSeededTorrents();
  }

  private void announceBuildArtifacts(@NotNull final SBuild build) {
    final File torrentsDir = getTorrentFilesBaseDir(build);
    BuildArtifacts artifacts = build.getArtifacts(BuildArtifactsViewMode.VIEW_DEFAULT);
    torrentsDir.mkdirs();
    artifacts.iterateArtifacts(new BuildArtifacts.BuildArtifactsProcessor() {
      @NotNull
      public Continuation processBuildArtifact(@NotNull BuildArtifact artifact) {
        if (shouldCreateTorrentFor(artifact)) {
          File artifactFile = new File(build.getArtifactsDirectory(), artifact.getRelativePath());
          File linkDir = getLinkDir(build);
          linkDir.mkdirs();

          try {
            File torrentFile = createTorrent(artifactFile, artifact.getRelativePath(), torrentsDir);
            myTorrentsDirectorySeeder.getTorrentSeeder().seedTorrent(torrentFile, artifactFile);
            FileLink.createLink(artifactFile, torrentFile, linkDir);
            } catch (IOException e) {
              e.printStackTrace();
          } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
          }
        }
        return Continuation.CONTINUE;
      }
    });
  }

  private File createTorrent(@NotNull final File artifactFile, @NotNull final String path, @NotNull final File torrentsDir){
    File destPath = new File(torrentsDir, path);
    final File parentDir = destPath.getParentFile();
    parentDir.mkdirs();

    return TorrentUtil.getOrCreateTorrent(artifactFile, torrentsDir, myTorrentTrackerManager.getAnnounceUri());
//            myTorrentTrackerManager.createTorrent(artifactFile, parentDir);
  }

  private boolean shouldCreateTorrentFor(@NotNull BuildArtifact artifact) {
    long size = artifact.getSize();
    return !artifact.isDirectory() && size >= myFileSizeThreshold * 1024 * 1024;
  }

  public void setMaxNumberOfSeededTorrents(int maxNumberOfSeededTorrents) {
    myTorrentsDirectorySeeder.setMaxTorrentsToSeed(maxNumberOfSeededTorrents);
  }


  private File getLinkDir(@NotNull SBuild build) {
    return new File(myTorrentsDirectorySeeder.getStorageDirectory(),
            build.getBuildTypeId() + File.separator + build.getBuildId());
  }

}
