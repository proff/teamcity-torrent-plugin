package jetbrains.buildServer.artifactsMirror;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.StreamUtil;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.tracker.TrackerHelper;
import jetbrains.buildServer.ArtifactsConstants;
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
import jetbrains.buildServer.artifactsMirror.torrent.TorrentUtil;
import jetbrains.buildServer.http.HttpUtil;
import jetbrains.buildServer.messages.BuildMessage1;
import jetbrains.buildServer.messages.DefaultMessagesInfo;
import jetbrains.buildServer.util.FileUtil;
import jetbrains.buildServer.util.StringUtil;
import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.*;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author Sergey.Pak
 *         Date: 7/31/13
 *         Time: 2:52 PM
 */
public class TorrentTransportFactory implements TransportFactoryExtension {

  private final static Logger LOG = Logger.getInstance(TorrentTransportFactory.class.getName());

  public static final String TEAMCITY_IVY = "teamcity-ivy.xml";
  public static final String TEAMCITY_TORRENTS = ArtifactsConstants.TEAMCITY_ARTIFACTS_DIR + "/torrents/";

  public static final int MIN_SEEDERS_COUNT_TO_TRY=2;

  public static final String TEAMCITY_ARTIFACTS_TRANSPORT = "teamcity.artifacts.transport";


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
      TorrentUtil.log2Build("Shouldn't use torrent transport for build type " + myBuildTracker.getCurrentBuild().getBuildTypeId(), buildLogger);
      return null;
    }

    if (NetworkUtil.isLocalHost(context.getServerUrl().getHost())) {
      TorrentUtil.log2Build("Shouldn't use torrent transport localhost", buildLogger);
      return null;
    }


    if (!myAgentTorrentsManager.isTorrentClientStarted()){
      TorrentUtil.log2Build("Agent torrent manager didn't start. Torrent transport is unavailable", buildLogger);
      return null;
    }

    return new TorrentTransport(myAgentTorrentsManager.getTorrentsDirectorySeeder(),
            createHttpClient(context),
            buildLogger);
  }

  private static boolean shouldUseTorrentTransport(@NotNull final AgentRunningBuild build) {
    final String param = build.getSharedConfigParameters().get(TEAMCITY_ARTIFACTS_TRANSPORT);
    return param != null && param.equals(TorrentTransport.class.getSimpleName());
  }

  protected static class TorrentTransport implements URLContentRetriever {

    private final HttpClient myClient;
    private final TeamcityTorrentClient mySeeder;
    private final TorrentsDirectorySeeder myDirectorySeeder;
    private final BuildProgressLogger myBuildLogger;

    private final Map<String, String> myTorrentsForArtifacts;

    protected TorrentTransport(@NotNull final TorrentsDirectorySeeder directorySeeder,
                               @NotNull final HttpClient client,
                               @NotNull final BuildProgressLogger buildLogger) {
      myDirectorySeeder = directorySeeder;
      mySeeder = myDirectorySeeder.getTorrentSeeder();
      myClient = client;
      myBuildLogger = buildLogger;
      myTorrentsForArtifacts = new HashMap<String, String>();
    }

    @Nullable
    public String downloadUrlTo(@NotNull final String urlString, @NotNull final File target) throws IOException {
      ParsedArtifactPath parsedArtifactUrl = new ParsedArtifactPath(urlString);
      if (urlString.endsWith(TEAMCITY_IVY)){
        // downloading teamcity-ivy.xml and parsing it:
        final String digest = parseArtifactsList(urlString, target);
        return digest;
      }

      Torrent torrent = downloadTorrent(parsedArtifactUrl);
      if (torrent == null) {
        return null;
      }

      try {
        myBuildLogger.progressStarted("Downloading " + target.getName() + " via torrent.");
        if (TrackerHelper.getSeedersCount(torrent) == 0) {
          log2Build("no seeders for " + urlString);
          return null;
        }
        final long startTime = System.currentTimeMillis();
        mySeeder.downloadAndShareOrFail(torrent, target, target.getParentFile(), getDownloadTimeoutSec(), MIN_SEEDERS_COUNT_TO_TRY);
        final long took = System.currentTimeMillis() - startTime + 1; // to avoid division by zero
        final long fileSize = target.length();
        log2Build(String.format("Download successfull. Avg speed %d kb/s. Saving torrent..", fileSize/took));
        File parentDir = getRealParentDir(target, parsedArtifactUrl.getArtifactPath());
        File torrentFile = new File(parentDir, parsedArtifactUrl.getTorrentPath());
        torrentFile.getParentFile().mkdirs();
        torrent.save(torrentFile);


        final File linkDir = new File(myDirectorySeeder.getStorageDirectory(), parsedArtifactUrl.getRelativeLinkPath()).getParentFile();
        linkDir.mkdirs();

        FileLink.createLink(target, torrentFile, linkDir);
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

    private String parseArtifactsList(@NotNull final String teamcityIvyUrl, @NotNull final File target) {
      try {
        byte[] ivyData = download(teamcityIvyUrl);
        XPath xpath = XPathFactory.newInstance().newXPath();
        NodeList artifactList = (NodeList) xpath.evaluate("/ivy-module/publications/artifact",
                new InputSource(new ByteArrayInputStream(ivyData)), XPathConstants.NODESET);
        Set<String> artifactsSet = new HashSet<String>();
        for (int i = 0; i < artifactList.getLength(); i++) {
          Node artifact = artifactList.item(i);
          final String artifactName = artifact.getAttributes().getNamedItem("name").getTextContent();
          final String artifactExt = artifact.getAttributes().getNamedItem("ext").getTextContent();
          if (!StringUtil.isEmpty(artifactExt)) {
            artifactsSet.add(artifactName + "." + artifactExt);
          } else {
            artifactsSet.add(artifactName);
          }
        }

        for (String s : artifactsSet) {
          if (s.startsWith(ArtifactsConstants.TEAMCITY_ARTIFACTS_DIR))
            continue;
          String proposedTorrentName = String.format("%s%s.torrent", TEAMCITY_TORRENTS, s);
          if (artifactsSet.contains(proposedTorrentName)){
            myTorrentsForArtifacts.put(s, proposedTorrentName);
          }
        }

        final NodeList info = (NodeList) xpath.evaluate("ivy-module/info",
                new InputSource(new ByteArrayInputStream(ivyData)), XPathConstants.NODESET);

        if (info.getLength()==1){
          final Node infoNode = info.item(0);
          final String module = infoNode.getAttributes().getNamedItem("module").getTextContent();
          final String revision = infoNode.getAttributes().getNamedItem("revision").getTextContent();
          FileUtils.writeByteArrayToFile(target, ivyData);
          return String.format("%s_%s_%s", TEAMCITY_IVY, module, revision);
        }

      } catch (Exception e) {
        log2Build(String.format("Unknown error while parsing %s: %s", TEAMCITY_IVY, e.getMessage()));
      }
      return null;
    }

    private void log2Build(String msg) {
      TorrentUtil.log2Build(msg, myBuildLogger);
    }

    @Nullable
    public String getDigest(@NotNull final String urlString) throws IOException {
      ParsedArtifactPath parsedArtifactUrl = new ParsedArtifactPath(urlString);
      Torrent torrent = downloadTorrent(parsedArtifactUrl);
      return torrent == null ? null : torrent.getHexInfoHash();
    }

    public void interrupt() {

    }

    private Torrent downloadTorrent(@NotNull final ParsedArtifactPath parsedArtifactUrl) {
      final String torrentRelativePath = myTorrentsForArtifacts.get(parsedArtifactUrl.getArtifactPath());
      if (torrentRelativePath == null)
        return null;

      try {
        byte[] torrentData = download(parsedArtifactUrl.getTorrentUrl());
        return new Torrent(torrentData, true);
      } catch (NoSuchAlgorithmException e) {
        LOG.error("NoSuchAlgorithmException", e);
      } catch (IOException e) {
        log2Build(String.format("Unable to download: %s", e.getMessage()));
      }
      return null;
    }

    protected byte[] download(final String urlString) throws IOException {
      final HttpMethod getMethod = new GetMethod(urlString);
      InputStream in = null;
      try {
        myClient.executeMethod(getMethod);
        if (getMethod.getStatusCode() != HttpStatus.SC_OK) {
          throw new IOException(String.format("Problem [%d] while downloading %s: %s", getMethod.getStatusCode(), urlString, getMethod.getStatusText()));
        }
        in = getMethod.getResponseBodyAsStream();
        ByteArrayOutputStream bOut = new ByteArrayOutputStream();
        StreamUtil.copyStreamContent(in, bOut);
        return bOut.toByteArray();
      } finally {
        FileUtil.close(in);
        getMethod.releaseConnection();
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
