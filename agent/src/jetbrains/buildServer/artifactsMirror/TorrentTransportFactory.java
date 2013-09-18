package jetbrains.buildServer.artifactsMirror;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.StreamUtil;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.tracker.TrackerHelper;
import jetbrains.buildServer.NetworkUtil;
import jetbrains.buildServer.agent.AgentRunningBuild;
import jetbrains.buildServer.agent.BuildProgressLogger;
import jetbrains.buildServer.agent.CurrentBuildTracker;
import jetbrains.buildServer.artifacts.DependencyResolverContext;
import jetbrains.buildServer.artifacts.TransportFactoryExtension;
import jetbrains.buildServer.artifacts.URLContentRetriever;
import jetbrains.buildServer.artifactsMirror.seeder.FileLink;
import jetbrains.buildServer.artifactsMirror.seeder.TorrentsDirectorySeeder;
import jetbrains.buildServer.artifactsMirror.torrent.TeamcityTorrentClient;
import jetbrains.buildServer.http.HttpUtil;
import jetbrains.buildServer.messages.BuildMessage1;
import jetbrains.buildServer.messages.DefaultMessagesInfo;
import jetbrains.buildServer.messages.ErrorData;
import jetbrains.buildServer.util.FileUtil;
import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Sergey.Pak
 *         Date: 7/31/13
 *         Time: 2:52 PM
 */
public class TorrentTransportFactory implements TransportFactoryExtension {

  private final static Logger LOG = Logger.getInstance(TorrentTransportFactory.class.getName());

  public static final String TEAMCITY_IVY = "teamcity-ivy.xml";
  public static final String TEAMCITY_TORRENTS = ".teamcity/torrents/";

  public static final String TEAMCITY_ARTIFACTS_TRANSPORT = "teamcity.artifacts.transport";


  private static final Pattern FILE_PATH_PATTERN = Pattern.compile("(.*?)/repository/download/([^/]+)/([^/]+)/(.+?)(\\?branch=.+)?");

  private final AgentTorrentsManager myAgentTorrentsManager;
  private final CurrentBuildTracker myBuildTracker;

  public TorrentTransportFactory(@NotNull final AgentTorrentsManager agentTorrentsManager,
                                 @NotNull final CurrentBuildTracker currentBuildTracker) {
    myAgentTorrentsManager = agentTorrentsManager;
    myBuildTracker = currentBuildTracker;
  }

  private HttpClient createHttpClient(@NotNull final DependencyResolverContext context) {
    HttpClient client = HttpUtil.createHttpClient(context.getConnectionTimeout());
    client.getParams().setAuthenticationPreemptive(true);
    Credentials defaultcreds = new UsernamePasswordCredentials(context.getUsername(), context.getPassword());
    client.getState().setCredentials(new AuthScope(AuthScope.ANY_HOST,
            AuthScope.ANY_PORT,
            AuthScope.ANY_REALM),
            defaultcreds);
    return client;
  }


  @Nullable
  public URLContentRetriever getTransport(@NotNull DependencyResolverContext context) {
    final BuildProgressLogger buildLogger = myBuildTracker.getCurrentBuild().getBuildLogger();
    if (!shouldUseTorrentTransport(myBuildTracker.getCurrentBuild())) {
      log2Build("Shouldn't use torrent transport for build type " + myBuildTracker.getCurrentBuild().getBuildTypeId(), buildLogger);
      return null;
    }
    if (NetworkUtil.isLocalHost(context.getServerUrl().getHost())) {
      log2Build("Shouldn't use torrent transport localhost", buildLogger);
      return null;
    }

    return new TorrentTransport(myAgentTorrentsManager.getTorrentsDirectorySeeder(),
            createHttpClient(context),
            buildLogger);
  }

  private static boolean shouldUseTorrentTransport(@NotNull final AgentRunningBuild build) {
    final String param = build.getSharedConfigParameters().get(TEAMCITY_ARTIFACTS_TRANSPORT);
    return param != null && param.contains(TorrentTransport.class.getSimpleName());
  }

  private static void log2Build(final String msg, final BuildProgressLogger buildLogger) {
    final BuildMessage1 textMessage = DefaultMessagesInfo.createTextMessage(msg);
    buildLogger.logMessage(DefaultMessagesInfo.internalize(textMessage));
  }

  protected static class TorrentTransport implements URLContentRetriever {

    private final HttpClient myClient;
    private final TeamcityTorrentClient mySeeder;
    private final TorrentsDirectorySeeder myDirectorySeeder;
    private final BuildProgressLogger myBuildLogger;

    protected TorrentTransport(@NotNull final TorrentsDirectorySeeder directorySeeder,
                               @NotNull final HttpClient client,
                               @NotNull final BuildProgressLogger buildLogger) {
      myDirectorySeeder = directorySeeder;
      mySeeder = myDirectorySeeder.getTorrentSeeder();
      myClient = client;
      myBuildLogger = buildLogger;
    }

    @Nullable
    public String downloadUrlTo(@NotNull final String urlString, @NotNull final File target) throws IOException {
      Torrent torrent = getTorrent(urlString);
      if (torrent == null) {
        return null;
      }
      try {
        myBuildLogger.progressStarted("Downloading " + target.getName() + " via torrent.");
        if (TrackerHelper.getSeedersCount(torrent) == 0) {
          log2Build("no seeders for " + urlString);
          return null;
        }

        mySeeder.downloadAndShareOrFail(torrent, target, target.getParentFile(), getDownloadTimeoutSec());
        log2Build("Download successfull. Saving torrent..");
        String realFilePath = getFilePathFromUrl(urlString);
        File parentDir = getRealParentDir(target, realFilePath);
        File torrentFile = new File(parentDir, TEAMCITY_TORRENTS + realFilePath + ".torrent");
        torrentFile.getParentFile().mkdirs();
        torrent.save(torrentFile);
        FileLink.createLink(target, torrentFile, myDirectorySeeder.getStorageDirectory());
        return torrent.getHexInfoHash();
      } catch (IOException e) {
        log2Build(String.format("Unable to download torrent for %s: %s", urlString, e.getMessage()));
        throw new IOException("Unable to download torrent for " + urlString, e);
      } catch (NoSuchAlgorithmException e) {
        throw new IOException("Unable to hash torrent for " + urlString, e);
      } catch (InterruptedException e) {
        throw new IOException("Torrent download has been interrupted " + urlString, e);
      } catch (RuntimeException ex) {
        log2Build(String.format("Unable to download artifact %s: %s", urlString, ex.getMessage()));
        throw ex;
      } finally {
        myBuildLogger.progressFinished();
      }
    }

    private void log2Build(String msg) {
      TorrentTransportFactory.log2Build(msg, myBuildLogger);
    }

    @Nullable
    public String getDigest(@NotNull final String urlString) throws IOException {
      Torrent torrent = getTorrent(urlString);
      return torrent == null ? null : torrent.getHexInfoHash();
    }

    protected Torrent getTorrent(@NotNull final String urlString) {
      if (urlString.contains(TEAMCITY_IVY)) {
        log2Build("Skip downloading teamcity-ivy.xml");
        return null;
      }
      log2Build("Downloading torrent for " + urlString);
      Torrent torrent = downloadTorrent(urlString);
      if (torrent == null) {
        log2Build("No torrent file for " + urlString);
        return null;
      }
      if (torrent.getSize() < myDirectorySeeder.getFileSizeThresholdMb() * 1024 * 1024) {
        log2Build(String.format("File size is lower than threshold of %dMb", myDirectorySeeder.getFileSizeThresholdMb()));
        return null;
      }
      return torrent;
    }

    protected Torrent downloadTorrent(@NotNull final String urlString) {
      // adding path here:
      final Matcher matcher = FILE_PATH_PATTERN.matcher(urlString);
      if (!matcher.matches()) {
        return null;
      }
      final StringBuilder b = new StringBuilder(urlString);
      final int realPathStart = matcher.start(4);
      b.insert(realPathStart, TEAMCITY_TORRENTS);
      int suffixPos = (matcher.group(5) == null ? b.length() : TEAMCITY_TORRENTS.length() + matcher.start(5));
      b.insert(suffixPos, ".torrent");
      String downloadUrl = b.toString();
      final GetMethod getMethod = new GetMethod(downloadUrl);
      InputStream in = null;
      try {
        myClient.executeMethod(getMethod);
        if (getMethod.getStatusCode() != HttpStatus.SC_OK) {
          throw new IOException("Problem [" + getMethod.getStatusCode() + "] while downloading " + downloadUrl + ": "
                  + getMethod.getStatusText());
        }
        in = getMethod.getResponseBodyAsStream();
        ByteArrayOutputStream bOut = new ByteArrayOutputStream();
        StreamUtil.copyStreamContent(in, bOut);
        byte[] torrentData = bOut.toByteArray();

        return new Torrent(torrentData, true);
      } catch (NoSuchAlgorithmException e) {
        LOG.error("NoSuchAlgorithmException", e);
      } catch (IOException e) {
        log2Build(String.format("Unable to download: %s", e.getMessage()));
      } finally {
        FileUtil.close(in);
        getMethod.releaseConnection();
      }
      return null;

    }

    private String getFilePathFromUrl(@NotNull final String urlString) {
      final Matcher matcher = FILE_PATH_PATTERN.matcher(urlString);
      if (matcher.matches()) {
        return matcher.group(4);
      } else {
        return null;
      }
    }

    private static File getRealParentDir(File file, String relativePath) {
      String path = file.getAbsolutePath().replaceAll("\\\\", "/");
      if (path.endsWith(relativePath)) {
        return new File(path.substring(0, path.length() - relativePath.length()));
      } else {
        return null;
      }
    }

    private long getDownloadTimeoutSec() {
      String strValue = System.getProperty("teamcity.torrent.download.timeout", "300");
      try {
        return Long.parseLong(strValue);
      } catch (NumberFormatException e) {
        return 300;
      }
    }

  }
}
