/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2013 ForgeRock AS. All Rights Reserved
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://forgerock.org/license/CDDLv1.0.html
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at http://forgerock.org/license/CDDLv1.0.html
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 */
package org.forgerock.openidm.maintenance.upgrade;

import static org.forgerock.json.JsonValue.array;
import static org.forgerock.json.JsonValue.field;
import static org.forgerock.json.JsonValue.json;
import static org.forgerock.json.JsonValue.object;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.jar.Attributes;

import difflib.DiffUtils;
import difflib.Patch;
import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import org.apache.commons.io.FileUtils;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.Service;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.ConnectionFactory;
import org.forgerock.json.resource.PatchOperation;
import org.forgerock.json.resource.PatchRequest;
import org.forgerock.json.resource.Requests;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.openidm.core.IdentityServer;
import org.forgerock.openidm.core.ServerConstants;
import org.forgerock.openidm.util.ContextUtil;
import org.forgerock.openidm.util.FileUtil;
import org.forgerock.services.context.Context;
import org.forgerock.util.Function;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Basic manager to initiate the product maintenance and upgrade mechanisms.
 */
@Component(name = UpdateManagerImpl.PID, policy = ConfigurationPolicy.IGNORE, metatype = true,
        description = "OpenIDM Update Manager", immediate = true)
@Service
@org.apache.felix.scr.annotations.Properties({
        @Property(name = Constants.SERVICE_VENDOR, value = ServerConstants.SERVER_VENDOR_NAME),
        @Property(name = Constants.SERVICE_DESCRIPTION, value = "Product Update Manager")
})
public class UpdateManagerImpl implements UpdateManager {

    /** The PID for this component. */
    public static final String PID = "org.forgerock.openidm.maintenance.updatemanager";

    private final static Logger logger = LoggerFactory.getLogger(UpdateManagerImpl.class);

    private static final Path CHECKSUMS_FILE = Paths.get(".checksums.csv");
    private static final Path BUNDLE_PATH = Paths.get("bundle");
    private static final Path CONF_PATH = Paths.get("conf");
    private static final String JSON_EXT = ".json";
    private static final String PATCH_EXT = ".patch";
    private static final Path ARCHIVE_PATH = Paths.get("bin/update");
    private static final String LICENSE_PATH = "legal-notices/license.txt";

    // Archive manifest keys
    private static final String PROP_PRODUCT = "product";
    private static final String PROP_VERSION = "version";
    private static final String PROP_UPGRADESPRODUCT = "upgradesProduct";
    private static final String PROP_UPGRADESVERSION = "upgradesVersion";
    private static final String PROP_DESCRIPTION = "description";
    private static final String PROP_RESOURCE = "resource";
    private static final String PROP_RESTARTREQUIRED = "restartRequired";

    private static final String BUNDLE_BACKUP_EXT = ".OLD-";
    private static final String BUNDLE_SYMBOLICNAME = "Bundle-SymbolicName";

    public enum UpdateStatus {
        IN_PROGRESS,
        COMPLETE,
        FAILED
    }

    /** The update logging service */
    @Reference(policy = ReferencePolicy.STATIC)
    private UpdateLogService updateLogService;

    /** The connection factory */
    @Reference(policy = ReferencePolicy.STATIC, target="(service.pid=org.forgerock.openidm.internal)")
    protected ConnectionFactory connectionFactory;

    /**
     * Execute a {@link Function} on an input stream for the given {@link Path}.
     *
     * @param path the {@link Path} on which to open an {@link InputStream}
     * @param function the {@link Function} to be applied to that {@link InputStream}
     * @param <R> The return type of the function
     * @param <E> The exception type thrown by the function
     * @return The result of the function
     * @throws E on exception from the function
     * @throws IOException on failure to create an input stream from the path given
     */
    static <R, E extends Exception> R withInputStreamForPath(Path path, Function<InputStream, R, E> function)
            throws E, IOException {
        try (final InputStream is = Files.newInputStream(path.normalize())) {
            return function.apply(is);
        }
    }

    private class ZipArchive implements Archive {
        private final Path upgradeRoot;
        private final Set<Path> filePaths;
        private final ProductVersion version;

        ZipArchive(Path zipFilePath, Path destination) throws ArchiveException {
            upgradeRoot = destination.resolve("openidm");

            // unzip the upgrade dist
            try {
                ZipFile zipFile = new ZipFile(zipFilePath.toString());
                if (zipFile.isEncrypted()) {
                    throw new UnsupportedOperationException("Encrypted zip files are not supported");
                }
                zipFile.extractAll(destination.toString());
            } catch (ZipException e) {
                throw new ArchiveException("Can't read archive file: " + zipFilePath.toAbsolutePath(), e);
            }

            // get the file set from the upgrade dist checksum file
            try {
                filePaths = new ChecksumFile(upgradeRoot.resolve(CHECKSUMS_FILE)).getFilePaths();
            } catch (Exception e) {
                throw new ArchiveException("Archive doesn't appear to contain checksums file - invalid archive?", e);
            }

            // get the version from the embedded ServerConstants
            try {
                final URL targetUrl = upgradeRoot
                        .resolve(BUNDLE_PATH)
                        .resolve(zipFilePath
                                .getFileName()
                                .toString()
                                .replaceAll("^openidm-(.*).zip$", "openidm-system-$1.jar"))
                        .toFile()
                        .toURI()
                        .toURL();
                try (final URLClassLoader loader = new URLClassLoader(new URL[] { targetUrl })) {
                    final Class<?> c = loader.loadClass("org.forgerock.openidm.core.ServerConstants");

                    final Method getVersion = c.getMethod("getVersion");
                    final Method getRevision = c.getMethod("getRevision");

                    version = new ProductVersion(
                            String.valueOf(getVersion.invoke(null)),
                            String.valueOf(getRevision.invoke(null)));
                }
            } catch (IOException
                    | ClassNotFoundException
                    | IllegalAccessException
                    | InvocationTargetException
                    | NoSuchMethodException e) {
                throw new ArchiveException("Can't determine product version from upgrade archive", e);
            }

            System.out.println("Upgrading to " + version);
        }

        @Override
        public ProductVersion getVersion() {
            return version;
        }

        @Override
        public Set<Path> getFiles() {
            return filePaths;
        }

        @Override
        public <R, E extends Exception> R withInputStreamForPath(Path path, Function<InputStream, R, E> function)
                throws E, IOException {
            return UpdateManagerImpl.withInputStreamForPath(upgradeRoot.resolve(path), function);
        }
    }

    /**
     * Execute a {@link Function} in a temp directory prefixed by {@code tempDirectoryPrefix}.  This function
     * is useful to do a body of work in a temp directory and then clean up the contents of the temp directory
     * and remove the temp directory once the work is complete.
     *
     * @param tempDirectoryPrefix the temp directory prefix
     * @param func a {@link Function} to execute in/on a temp directory
     * @param <R> The return type of the function
     * @return the result of the function
     * @throws UpdateException on failure to create the temp directory or execute the function
     */
    private <R> R withTempDirectory(String tempDirectoryPrefix, Function<Path, R, UpdateException> func)
            throws UpdateException {

        Path tempUnzipDir = null;
        try {
            tempUnzipDir = Files.createTempDirectory(tempDirectoryPrefix);
            return func.apply(tempUnzipDir);
        } catch (IOException e) {
            throw new UpdateException("Cannot create temporary directory to unzip archive");
        } finally {
            try {
                if (tempUnzipDir != null) {
                    FileUtils.deleteDirectory(tempUnzipDir.toFile());
                }
            } catch (IOException e) {
                logger.error("Could not remove temporary directory: " + tempUnzipDir.toString(), e);
            }
        }
    }

    /**
     * An interface for upgrade actions.
     *
     * @param <R> the return type (often JsonValue)
     */
    private interface UpgradeAction<R> {
        R invoke(Archive archive, FileStateChecker fileStateChecker) throws UpdateException;
    }

    /**
     * Invoke an {@link UpgradeAction} using an archive at a given URL.  Use {@code installDir} as the location
     * of the currently-installed OpenIDM.
     *
     * @param archiveFile the {@link Path} to a ZIP archive containing a new version of OpenIDM
     * @param installDir the {@link Path} of the currently-installed OpenIDM
     * @param upgradeAction the upgrade action to perform
     * @param <R> The return type of the action
     * @return the result of the upgrade action
     * @throws UpdateException on failure to perform the upgrade action
     */
    private <R> R usingArchive(final Path archiveFile, final Path installDir, final UpgradeAction<R> upgradeAction)
            throws UpdateException {

        return withTempDirectory("openidm-upgrade-",
                new Function<Path, R, UpdateException>() {
                    @Override
                    public R apply(Path tempUnzipDir) throws UpdateException {
                        try {
                            final ZipArchive archive = new ZipArchive(archiveFile, tempUnzipDir);
                            final ChecksumFile checksumFile = new ChecksumFile(installDir.resolve(CHECKSUMS_FILE));
                            final FileStateChecker fileStateChecker = new FileStateChecker(checksumFile);

                            return upgradeAction.invoke(archive, fileStateChecker);
                        } catch (Exception e) {
                            throw new UpdateException(e.getMessage(), e);
                        }
                    }
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JsonValue listAvailableUpdates() throws UpdateException {
        final JsonValue updates = json(array());

        final ChecksumFile checksumFile;
        try {
            checksumFile = new ChecksumFile(Paths.get(".").resolve(CHECKSUMS_FILE));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new UpdateException("Failed to load checksum file from archive.", e);
        } catch (NullPointerException e) {
            throw new UpdateException("Archive directory does not exist?", e);
        }

        for (final File file : ARCHIVE_PATH.toFile().listFiles()) {
            if (file.getName().endsWith(".zip")) {
                try {
                    Properties prop = readProperties(file);
                    if ("OpenIDM".equals(prop.getProperty(PROP_UPGRADESPRODUCT)) &&
                            ServerConstants.getVersion().equals(prop.getProperty(PROP_UPGRADESVERSION))) {
                        updates.add(object(
                                field("archive", file.getName()),
                                field("fileSize", file.length()),
                                field("fileDate", file.lastModified()),
                                field("checksum", checksumFile.getCurrentDigest(file.toPath())),
                                field("version", prop.getProperty(PROP_PRODUCT) + " v" +
                                        prop.getProperty(PROP_VERSION)),
                                field("description", prop.getProperty(PROP_DESCRIPTION)),
                                field("resource", prop.getProperty(PROP_RESOURCE)),
                                field("restartRequired", prop.getProperty(PROP_RESTARTREQUIRED))
                        ));
                    }
                } catch (Exception e) {
                    // skip file, it does not contain a manifest or digest could not be calculated
                }
            }
        }

        return updates;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JsonValue report(final Path archiveFile, final Path installDir)
            throws UpdateException {

        return usingArchive(archiveFile, installDir,
                new UpgradeAction<JsonValue>() {
                    @Override
                    public JsonValue invoke(Archive archive, FileStateChecker fileStateChecker)
                            throws UpdateException {

                        final List<Object> result = array();
                        for (Path path : archive.getFiles()) {
                            try {
                                FileState state = fileStateChecker.getCurrentFileState(path);
                                if (!FileState.UNCHANGED.equals(state)) {
                                    result.add(object(
                                            field("filePath", path.toString()),
                                            field("fileState", state.toString())
                                    ));
                                }
                            } catch (IOException e) {
                                throw new UpdateException("Unable to determine file state for " + path.toString(), e);
                            }
                        }

                        return json(result);
                    }
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JsonValue diff(final Path archiveFile, final Path installDir, final String filename) throws UpdateException {

        return usingArchive(archiveFile, installDir,
                new UpgradeAction<JsonValue>() {
                    // Helper function for get the file content
                    private Function<InputStream, List<String>, IOException> inputStreamToLines =
                            new Function<InputStream, List<String>, IOException>() {
                                @Override
                                public List<String> apply(InputStream is) throws IOException {
                                    final List<String> lines = new LinkedList<String>();
                                    String line = "";
                                    try (final InputStreamReader isr = new InputStreamReader(is);
                                         final BufferedReader in = new BufferedReader(isr)) {
                                        while ((line = in.readLine()) != null) {
                                            lines.add(line);
                                        }
                                    }
                                    return lines;
                                }
                            };

                    @Override
                    public JsonValue invoke(Archive archive, FileStateChecker fileStateChecker) throws UpdateException {
                        final Path file = Paths.get(filename);

                        try {
                            final List<String> currentFileLines = withInputStreamForPath(installDir.resolve(file), inputStreamToLines);
                            final List<String> newFileLines = archive.withInputStreamForPath(file, inputStreamToLines);
                            Patch<String> patch = DiffUtils.diff(currentFileLines, newFileLines);
                            return json(object(
                                    field("current", currentFileLines),
                                    field("new", newFileLines),
                                    field("diff", DiffUtils.generateUnifiedDiff(
                                            Paths.get("current").resolve(file).toString(),
                                            Paths.get("new").resolve(file).toString(),
                                            currentFileLines,
                                            patch,
                                            3))));
                        } catch (IOException e) {
                            throw new UpdateException("Unable to retrieve file content for " + file.toString(), e);
                        }
                    }
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JsonValue upgrade(final Path archiveFile, final Path installDir, final String userName,
            final BundleContext bundleContext) throws UpdateException {

        final Properties prop = readProperties(archiveFile.toFile());
        if (!"OpenIDM".equals(prop.getProperty(PROP_UPGRADESPRODUCT)) ||
                !ServerConstants.getVersion().equals(prop.getProperty(PROP_UPGRADESVERSION))) {
            throw new UpdateException("Update archive does not apply to the installed product.");
        }

        Path tempUnzipDir = null;
        try {
            tempUnzipDir = Files.createTempDirectory("openidm-upgrade-");
            try {
                final ZipArchive archive = new ZipArchive(archiveFile, tempUnzipDir);
                final ChecksumFile checksumFile = new ChecksumFile(installDir.resolve(CHECKSUMS_FILE));
                final FileStateChecker fileStateChecker = new FileStateChecker(checksumFile);

                // perform upgrade
                UpdateLogEntry updateEntry = new UpdateLogEntry();
                updateEntry.setStatus(UpdateStatus.IN_PROGRESS)
                        .setStatusMessage("Initializing update")
                        .setTotalTasks(archive.getFiles().size())
                        .setStartDate(getDateString())
                        .setNodeId(IdentityServer.getInstance().getNodeName())
                        .setUserName(userName);
                try {
                    updateLogService.logUpdate(updateEntry);
                } catch (ResourceException e) {
                    throw new UpdateException("Unable to log update.", e);
                }

                new UpdateThread(updateEntry, archive, fileStateChecker, installDir, prop, tempUnzipDir, bundleContext)
                        .start();

                return updateEntry.toJson();
            } catch (Exception e) {
                throw new UpdateException(e.getMessage(), e);
            }
        } catch (IOException e) {
            throw new UpdateException("Cannot create temporary directory to unzip archive");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JsonValue getLicense(Path archive) throws UpdateException {
        try {
            ZipFile zip = new ZipFile(archive.toFile());
            Path tmpDir = Files.createTempDirectory(UUID.randomUUID().toString());
            zip.extractFile("openidm/" + LICENSE_PATH, tmpDir.toString());
            File file = new File(tmpDir.toString() + "openidm/" + LICENSE_PATH);
            if (!file.exists()) {
                throw new UpdateException("Unable to locate a license file.");
            }
            try (FileInputStream inp = new FileInputStream(file)) {
                byte[] data = new byte[(int) file.length()];
                inp.read(data);
                return json(object(field("license", new String(data, "UTF-8"))));
            } catch (IOException e) {
                throw new UpdateException("Unable to load package properties.", e);
            }
        } catch (IOException | ZipException e) {
            throw new UpdateException("Unable to load package properties.", e);
        }
    }

    private class UpdateThread extends Thread {
        private final UpdateLogEntry updateEntry;
        private final Archive archive;
        private final FileStateChecker fileStateChecker;
        private final StaticFileUpdate staticFileUpdate;
        private final Properties updateProperties;
        private final Path tempDirectory;
        private final BundleContext bundleContext;
        private final long timestamp = new Date().getTime();

        public UpdateThread(UpdateLogEntry updateEntry, Archive archive, FileStateChecker fileStateChecker,
                Path installDir, Properties updateProperties, Path tempDirectory, BundleContext bundleContext) {
            this.updateEntry = updateEntry;
            this.archive = archive;
            this.fileStateChecker = fileStateChecker;
            this.updateProperties = updateProperties;
            this.tempDirectory = tempDirectory;
            this.bundleContext = bundleContext;

            this.staticFileUpdate = new StaticFileUpdate(fileStateChecker, installDir,
                    archive, new ProductVersion(ServerConstants.getVersion(),
                    ServerConstants.getRevision()), timestamp);
        }

        public void run() {
            try {
                for (final Path path : archive.getFiles()) {
                    try {
                        if (path.startsWith(BUNDLE_PATH)) {
                            BundleHandler bundleHandler = new BundleHandler(bundleContext,
                                    BUNDLE_BACKUP_EXT + timestamp);
                            Path newPath = Paths.get(tempDirectory.toString(), "openidm", path.toString());
                            Attributes manifest = readManifest(newPath);
                            String symbolicName = manifest.getValue(BUNDLE_SYMBOLICNAME);
                            if (symbolicName == null) {
                                // treat it as a static file
                                Path backupFile = staticFileUpdate.replace(path);
                                if (backupFile != null) {
                                    UpdateFileLogEntry fileEntry = new UpdateFileLogEntry()
                                            .setFilePath(path.toString())
                                            .setFileState(fileStateChecker.getCurrentFileState(path).name());
                                    fileEntry.setBackupFile(backupFile.toString());
                                    logUpdate(updateEntry.addFile(fileEntry.toJson()));
                                }
                            } else {
                                bundleHandler.upgradeBundle(newPath, symbolicName);
                            }
                        } else if (path.startsWith(CONF_PATH) &&
                                path.getFileName().toString().endsWith(JSON_EXT)) {
                            // a json config in the default project - ignore it
                        } else if (path.startsWith(CONF_PATH) &&
                                path.getFileName().toString().endsWith(PATCH_EXT)) {
                            patchConfig(ContextUtil.createInternalContext(),
                                    "repo/config", json(FileUtil.readFile(path.toFile())));
                        } else {
                            // normal static file; update it
                            UpdateFileLogEntry fileEntry = new UpdateFileLogEntry()
                                    .setFilePath(path.toString())
                                    .setFileState(fileStateChecker.getCurrentFileState(path).name());

                            boolean readOnly = path.startsWith("ui/default") || path.startsWith("bin");

                            if (!readOnly) {
                                Path stockFile = staticFileUpdate.keep(path);
                                if (stockFile != null) {
                                    fileEntry.setStockFile(stockFile.toString());
                                }
                            } else {
                                Path backupFile = staticFileUpdate.replace(path);
                                if (backupFile != null) {
                                    fileEntry.setBackupFile(backupFile.toString());
                                }
                            }

                            if (fileEntry.getStockFile() != null || fileEntry.getBackupFile() != null) {
                                logUpdate(updateEntry.addFile(fileEntry.toJson()));
                            }
                        }
                    } catch (IOException e) {
                        logUpdate(updateEntry.setEndDate(getDateString())
                                .setStatus(UpdateStatus.FAILED)
                                .setStatusMessage("Update failed."));
                        throw new UpdateException("Unable to upgrade " + path.toString(), e);
                    }
                    logUpdate(updateEntry.setCompletedTasks(updateEntry.getCompletedTasks() + 1)
                            .setStatusMessage("Processed " + path.getFileName().toString()));
                }
                logUpdate(updateEntry.setEndDate(getDateString())
                        .setStatus(UpdateStatus.COMPLETE)
                        .setStatusMessage("Update complete."));
            } catch (Exception e) {
                logger.debug("Failed to install update!", e);
                return;
            } finally {
                try {
                    if (tempDirectory != null) {
                        FileUtils.deleteDirectory(tempDirectory.toFile());
                    }
                } catch (IOException e) {
                    logger.error("Could not remove temporary directory: " + tempDirectory.toString(), e);
                }
            }

            if (Boolean.valueOf(updateProperties.getProperty(PROP_RESTARTREQUIRED).toUpperCase())) {
                // TODO rewire / restart
            }
        }

        /**
         * Apply a JsonPatch to a config object on the router.
         *
         * @param context the context for the patch request.
         * @param resourceName the name of the resource to be patched.
         * @param patch a JsonPatch to be applied to the named config resource.
         * @throws UpdateException
         */
        private void patchConfig(Context context, String resourceName, JsonValue patch) throws UpdateException {
            try {
                PatchRequest request = Requests.newPatchRequest(resourceName);
                for (PatchOperation op : PatchOperation.valueOfList(patch)) {
                    request.addPatchOperation(op);
                }
                UpdateManagerImpl.this.connectionFactory.getConnection().patch(context, request);
            } catch (ResourceException e) {
                throw new UpdateException("Patch request failed", e);
            }
        }
    }

    private void logUpdate(UpdateLogEntry entry) throws UpdateException {
        try {
            updateLogService.updateUpdate(entry);
        } catch (ResourceException e) {
            throw new UpdateException("Failed to modify update log entry.", e);
        }
    }

    private String getDateString() {
        // Ex: 2011-09-09T14:58:17.654+02:00
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ss.SXXX");
        return formatter.format(new Date());
    }

    private Properties readProperties(File file) throws UpdateException {
        Properties prop = new Properties();
        try {
            ZipFile zip = new ZipFile(file);
            Path tmpDir = Files.createTempDirectory(UUID.randomUUID().toString());
            zip.extractFile("openidm/package.properties", tmpDir.toString());
            try (InputStream inp = new FileInputStream(tmpDir.toString() + "/openidm/package.properties")) {
                prop.load(inp);
                return prop;
            } catch (IOException e) {
                throw new UpdateException("Unable to load package properties.", e);
            }
        } catch (IOException | ZipException e) {
            throw new UpdateException("Unable to load package properties.", e);
        }
    }

    private Attributes readManifest(Path jarFile) throws UpdateException {
        try {
            return FileUtil.readManifest(jarFile.toFile());
        } catch (FileNotFoundException e) {
            throw new UpdateException("File " + jarFile.toFile().getName() + " does not exist.", e);
        } catch (IOException e) {
            throw new UpdateException("Error while reading from " + jarFile.toFile().getName(), e);
        }
    }

    /**
     * Fetch the zip file from the URL and write it to the local filesystem.
     *
     * @param url
     * @return
     * @throws UpdateException
     */
    private Path readZipFile(URL url) throws UpdateException {

        // Download the patch file
        final ReadableByteChannel channel;
        try {
            channel = Channels.newChannel(url.openStream());
        } catch (IOException ex) {
            throw new UpdateException("Failed to access the specified file " + url + " " + ex.getMessage(), ex);
        }

        String workingDir = "";
        final String targetFileName = new File(url.getPath()).getName();
        final File patchDir = new File(workingDir, "patch/bin");
        patchDir.mkdirs();
        final File targetFile = new File(patchDir, targetFileName);
        final FileOutputStream fos;
        try {
            fos = new FileOutputStream(targetFile);
        } catch (FileNotFoundException ex) {
            throw new UpdateException("Error in getting the specified file to " + targetFile, ex);
        }

        try {
            fos.getChannel().transferFrom(channel, 0, Long.MAX_VALUE);
            System.out.println("Downloaded to " + targetFile);
        } catch (IOException ex) {
            throw new UpdateException("Failed to get the specified file " + url + " to: " + targetFile, ex);
        }

        return targetFile.toPath();
    }
}
