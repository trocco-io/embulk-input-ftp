package org.embulk.input.ftp;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigException;
import org.embulk.config.ConfigSource;
import org.embulk.config.TaskReport;
import org.embulk.config.TaskSource;
import org.embulk.spi.Exec;
import org.embulk.spi.FileInputPlugin;
import org.embulk.spi.TransactionalFileInput;
import org.embulk.util.config.Config;
import org.embulk.util.config.ConfigDefault;
import org.embulk.util.config.ConfigMapper;
import org.embulk.util.config.ConfigMapperFactory;
import org.embulk.util.config.Task;
import org.embulk.util.config.TaskMapper;
import org.embulk.util.file.InputStreamFileInput;
import org.embulk.util.file.InputStreamFileInput.InputStreamWithHints;
import org.embulk.util.file.ResumableInputStream;
import org.embulk.util.retryhelper.RetryExecutor;
import org.embulk.util.retryhelper.RetryGiveupException;
import org.embulk.util.retryhelper.Retryable;
import org.embulk.util.ssl.SSLPlugins;
import org.embulk.util.ssl.SSLPlugins.SSLPluginConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.sauronsoftware.ftp4j.FTPAbortedException;
import it.sauronsoftware.ftp4j.FTPClient;
import it.sauronsoftware.ftp4j.FTPCommunicationListener;
import it.sauronsoftware.ftp4j.FTPConnector;
import it.sauronsoftware.ftp4j.FTPDataTransferException;
import it.sauronsoftware.ftp4j.FTPDataTransferListener;
import it.sauronsoftware.ftp4j.FTPException;
import it.sauronsoftware.ftp4j.FTPFile;
import it.sauronsoftware.ftp4j.FTPIllegalReplyException;
import it.sauronsoftware.ftp4j.FTPListParseException;

public class FtpFileInputPlugin
        implements FileInputPlugin
{
    private static final ConfigMapperFactory CONFIG_MAPPER_FACTORY = ConfigMapperFactory.builder().addDefaultModules().build();

    private final Logger log = LoggerFactory.getLogger(FtpFileInputPlugin.class);
    private static final int FTP_DEFULAT_PORT = 21;
    private static final int FTPS_DEFAULT_PORT = 990;
    private static final int FTPES_DEFAULT_PORT = 21;

    public interface PluginTask
            extends Task, SSLPlugins.SSLPluginTask
    {
        @Config("path_prefix")
        String getPathPrefix();

        @Config("last_path")
        @ConfigDefault("null")
        Optional<String> getLastPath();

        @Config("path_match_pattern")
        @ConfigDefault("\".*\"")
        String getPathMatchPattern();

        @Config("incremental")
        @ConfigDefault("true")
        boolean getIncremental();

        @Config("host")
        String getHost();

        @Config("port")
        @ConfigDefault("null")
        Optional<Integer> getPort();

        @Config("user")
        @ConfigDefault("null")
        Optional<String> getUser();

        @Config("password")
        @ConfigDefault("null")
        Optional<String> getPassword();

        @Config("passive_mode")
        @ConfigDefault("true")
        boolean getPassiveMode();

        @Config("ascii_mode")
        @ConfigDefault("false")
        boolean getAsciiMode();

        @Config("ssl")
        @ConfigDefault("false")
        boolean getSsl();

        @Config("ssl_explicit")
        @ConfigDefault("true")
        boolean getSslExplicit();

        @Config("stop_when_file_not_found")
        @ConfigDefault("false")
        boolean getStopWhenFileNotFound();

        List<String> getFiles();
        void setFiles(List<String> files);

        SSLPluginConfig getSSLConfig();
        void setSSLConfig(SSLPluginConfig config);
    }

    @Override
    public ConfigDiff transaction(final ConfigSource config, final FileInputPlugin.Control control)
    {
        final ConfigMapper configMapper = CONFIG_MAPPER_FACTORY.createConfigMapper();
        final PluginTask task = configMapper.map(config, PluginTask.class);

        task.setSSLConfig(SSLPlugins.configure(task));

        String pattern = task.getPathMatchPattern();
        // If pattern is empty then use default pattern
        if (pattern != null && pattern.trim().isEmpty()) {
            pattern = ".*";
        }
        // Create path match pattern
        final Pattern pathMatchPattern = Pattern.compile(pattern);

        // list files recursively
        final List<String> files = listFiles(log, task, pathMatchPattern);
        task.setFiles(files);
        log.info("Using files {}", files);

        // TODO what if task.getFiles().isEmpty()?

        // number of processors is same with number of files
        return resume(task.toTaskSource(), task.getFiles().size(), control);
    }

    @Override
    public ConfigDiff resume(final TaskSource taskSource,
            final int taskCount,
            final FileInputPlugin.Control control)
    {
        final TaskMapper taskMapper = CONFIG_MAPPER_FACTORY.createTaskMapper();
        final PluginTask task = taskMapper.map(taskSource, PluginTask.class);

        control.run(taskSource, taskCount);

        // build next config
        final ConfigDiff configDiff = CONFIG_MAPPER_FACTORY.newConfigDiff();

        // last_path
        if (task.getIncremental()) {
            if (task.getFiles().isEmpty()) {
                // keep the last value
                if (task.getLastPath().isPresent()) {
                    configDiff.set("last_path", task.getLastPath().get());
                }
            }
            else {
                final List<String> files = new ArrayList<String>(task.getFiles());
                Collections.sort(files);
                configDiff.set("last_path", files.get(files.size() - 1));
            }
        }

        return configDiff;
    }

    @Override
    public void cleanup(final TaskSource taskSource,
            final int taskCount,
            final List<TaskReport> successTaskReports)
    {
        // do nothing
    }

    private static FTPClient newFTPClient(final Logger log, final PluginTask task)
    {
        FTPClient client = new FTPClient();
        try {
            int defaultPort = FTP_DEFULAT_PORT;
            if (task.getSsl()) {
                client.setSSLSocketFactory(SSLPlugins.newSSLSocketFactory(task.getSSLConfig(), task.getHost()));
                if (task.getSslExplicit()) {
                    client.setSecurity(FTPClient.SECURITY_FTPES);
                    defaultPort = FTPES_DEFAULT_PORT;
                    log.info("Using FTPES(FTPS/explicit) mode");
                }
                else {
                    client.setSecurity(FTPClient.SECURITY_FTPS);
                    defaultPort = FTPS_DEFAULT_PORT;
                    log.info("Using FTPS(FTPS/implicit) mode");
                }
            }
            final int port = task.getPort().isPresent() ? task.getPort().get() : defaultPort;

            client.addCommunicationListener(new LoggingCommunicationListner(log));

            // TODO configurable timeout parameters
            client.setAutoNoopTimeout(3000);

            final FTPConnector con = client.getConnector();
            con.setConnectionTimeout(30);
            con.setReadTimeout(60);
            con.setCloseTimeout(60);

            // for commons-net client
            //client.setControlKeepAliveTimeout
            //client.setConnectTimeout
            //client.setSoTimeout
            //client.setDataTimeout
            //client.setAutodetectUTF8

            client.connect(task.getHost(), port);
            log.info("Connecting to {}:{}", task.getHost(), port);

            if (task.getUser().isPresent()) {
                log.info("Logging in with user " + task.getUser().get());
                client.login(task.getUser().get(), task.getPassword().orElse(""));
            }

            log.info("Using passive mode");
            client.setPassive(task.getPassiveMode());

            if (task.getAsciiMode()) {
                log.info("Using ASCII mode");
                client.setType(FTPClient.TYPE_TEXTUAL);
            }
            else {
                log.info("Using binary mode");
                client.setType(FTPClient.TYPE_BINARY);
            }

            if (client.isCompressionSupported()) {
                log.info("Using MODE Z compression");
                client.setCompressionEnabled(true);
            }

            final FTPClient connected = client;
            client = null;
            return connected;
        }
        catch (final FTPException ex) {
            log.info("FTP command failed: " + ex.getCode() + " " + ex.getMessage());
            throw new RuntimeException(ex);
        }
        catch (final FTPIllegalReplyException ex) {
            log.info("FTP protocol error");
            throw new RuntimeException(ex);
        }
        catch (final IOException ex) {
            log.info("FTP network error: " + ex);
            throw new UncheckedIOException(ex);
        }
        finally {
            if (client != null) {
                disconnectClient(client);
            }
        }
    }

    static void disconnectClient(final FTPClient client)
    {
        if (client.isConnected()) {
            try {
                client.disconnect(false);
            }
            catch (final FTPException ex) {
                // do nothing
            }
            catch (final FTPIllegalReplyException ex) {
                // do nothing
            }
            catch (final IOException ex) {
                // do nothing
            }
        }
    }

    private List<String> listFiles(final Logger log, final PluginTask task, final Pattern pathMatchPattern)
    {
        final FTPClient client = newFTPClient(log, task);
        try {
            List<String> files = listFilesByPrefix(log, client, task.getPathPrefix(), task.getLastPath(), pathMatchPattern);
            if (files.isEmpty() && task.getStopWhenFileNotFound()) {
                throw new ConfigException("No file is found. \"stop_when_file_not_found\" option is \"true\".");
            }

            return files;
        }
        finally {
            disconnectClient(client);
        }
    }

    public static List<String> listFilesByPrefix(final Logger log, final FTPClient client,
            final String prefix, final Optional<String> lastPath, final Pattern pathMatchPattern)
    {
        String directory;
        String fileNamePrefix;
        if (prefix.isEmpty()) {
            directory = "";
            fileNamePrefix = "";
        }
        else {
            final int pos = prefix.lastIndexOf("/");
            if (pos < 0) {
                directory = "";
                fileNamePrefix = prefix;
            }
            else {
                directory = prefix.substring(0, pos + 1);  // include last "/"
                fileNamePrefix = prefix.substring(pos + 1);
            }
        }

        final ArrayList<String> builder = new ArrayList<>();

        try {
            String currentDirectory = client.currentDirectory();
            log.info("Listing ftp files at directory '{}' filtering filename by prefix '{}'", directory.isEmpty() ? currentDirectory : directory, fileNamePrefix);

            if (!directory.isEmpty()) {
                client.changeDirectory(directory);
                currentDirectory = directory;
            }

            for (final FTPFile file : client.list()) {
                if (file.getName().startsWith(fileNamePrefix)) {
                    listFilesRecursive(client, currentDirectory, file, lastPath, builder, pathMatchPattern);
                }
            }
        }
        catch (final FTPListParseException ex) {
            log.info("FTP listing files failed");
            throw new RuntimeException(ex);
        }
        catch (final FTPAbortedException ex) {
            log.info("FTP listing files failed");
            throw new RuntimeException(ex);
        }
        catch (final FTPDataTransferException ex) {
            log.info("FTP data transfer failed");
            throw new RuntimeException(ex);
        }
        catch (final FTPException ex) {
            log.info("FTP command failed: " + ex.getCode() + " " + ex.getMessage());
            throw new RuntimeException(ex);
        }
        catch (final FTPIllegalReplyException ex) {
            log.info("FTP protocol error");
            throw new RuntimeException(ex);
        }
        catch (final IOException ex) {
            log.info("FTP network error: " + ex);
            throw new UncheckedIOException(ex);
        }

        return Collections.unmodifiableList(builder);
    }

    private static void listFilesRecursive(final FTPClient client,
            String baseDirectoryPath, final FTPFile file, final Optional<String> lastPath,
            final ArrayList<String> builder, final Pattern pathMatchPattern)
        throws IOException, FTPException, FTPIllegalReplyException, FTPDataTransferException, FTPAbortedException, FTPListParseException
    {
        if (!baseDirectoryPath.endsWith("/")) {
            baseDirectoryPath = baseDirectoryPath + "/";
        }
        final String path = baseDirectoryPath + file.getName();

        if (lastPath.isPresent() && path.compareTo(lastPath.get()) <= 0) {
            return;
        }

        switch (file.getType()) {
        case FTPFile.TYPE_FILE:
            if (pathMatchPattern.matcher(path).find()) {
                builder.add(path);
            }
            break;
        case FTPFile.TYPE_DIRECTORY:
            client.changeDirectory(path);
            for (final FTPFile subFile : client.list()) {
                listFilesRecursive(client, path, subFile, lastPath, builder, pathMatchPattern);
            }
            client.changeDirectory(baseDirectoryPath);
            break;
        case FTPFile.TYPE_LINK:
            // TODO
        }
    }

    @Override
    public TransactionalFileInput open(final TaskSource taskSource, final int taskIndex)
    {
        final TaskMapper taskMapper = CONFIG_MAPPER_FACTORY.createTaskMapper();
        final PluginTask task = taskMapper.map(taskSource, PluginTask.class);
        return new FtpFileInput(log, task, taskIndex);
    }

    private static class LoggingCommunicationListner
            implements FTPCommunicationListener
    {
        private final Logger log;

        public LoggingCommunicationListner(final Logger log)
        {
            this.log = log;
        }

        @Override
        public void received(final String statement)
        {
            log.info("< " + statement);
        }

        @Override
        public void sent(final String statement)
        {
            if (statement.startsWith("PASS")) {
                // don't show password
                return;
            }
            log.info("> " + statement);
        }
    }

    private static class LoggingTransferListener
            implements FTPDataTransferListener
    {
        private final Logger log;
        private final long transferNoticeBytes;

        private long totalTransfer;
        private long nextTransferNotice;

        public LoggingTransferListener(final Logger log, final long transferNoticeBytes)
        {
            this.log = log;
            this.transferNoticeBytes = transferNoticeBytes;
            this.nextTransferNotice = transferNoticeBytes;
        }

        @Override
        public void started()
        {
            log.info("Transfer started");
        }

        @Override
        public void transferred(final int length)
        {
            totalTransfer += length;
            if (totalTransfer > nextTransferNotice) {
                log.info("Transferred " + totalTransfer + " bytes");
                nextTransferNotice = ((totalTransfer / transferNoticeBytes) + 1) * transferNoticeBytes;
            }
        }

        @Override
        public void completed()
        {
            log.info("Transfer completed " + totalTransfer + " bytes");
        }

        @Override
        public void aborted()
        {
            log.info("Transfer aborted");
        }

        @Override
        public void failed()
        {
            log.info("Transfer failed");
        }
    }

    private static final long TRANSFER_NOTICE_BYTES = 100 * 1024 * 1024;

    private static InputStream startDownload(final Logger log, final FTPClient client,
            final String path, final long offset, final ExecutorService executor)
    {
        final BlockingTransfer t = BlockingTransfer.submit(executor,
                new Function<BlockingTransfer, Runnable>()
                {
                    @Override
                    public Runnable apply(final BlockingTransfer transfer)
                    {
                        return new Runnable() {
                            @Override
                            public void run()
                            {
                                try {
                                    client.download(path, Channels.newOutputStream(transfer.getWriterChannel()), offset, new LoggingTransferListener(log, TRANSFER_NOTICE_BYTES));
                                }
                                catch (final FTPException ex) {
                                    log.info("FTP command failed: " + ex.getCode() + " " + ex.getMessage());
                                    throw new RuntimeException(ex);
                                }
                                catch (final FTPDataTransferException ex) {
                                    log.info("FTP data transfer failed");
                                    throw new RuntimeException(ex);
                                }
                                catch (final FTPAbortedException ex) {
                                    log.info("FTP listing files failed");
                                    throw new RuntimeException(ex);
                                }
                                catch (final FTPIllegalReplyException ex) {
                                    log.info("FTP protocol error");
                                    throw new RuntimeException(ex);
                                }
                                catch (final IOException ex) {
                                    throw new UncheckedIOException(ex);
                                }
                                finally {
                                    try {
                                        transfer.getWriterChannel().close();
                                    }
                                    catch (final IOException ex) {
                                        throw new UncheckedIOException(ex);
                                    }
                                }
                            }
                        };
                    }
                });
        return Channels.newInputStream(t.getReaderChannel());
    }

    private static class FtpInputStreamReopener
            implements ResumableInputStream.Reopener
    {
        private final Logger log;
        private final FTPClient client;
        private final ExecutorService executor;
        private final String path;

        public FtpInputStreamReopener(final Logger log, final FTPClient client, final ExecutorService executor, final String path)
        {
            this.log = log;
            this.client = client;
            this.executor = executor;
            this.path = path;
        }

        @Override
        public InputStream reopen(final long offset, final Exception closedCause) throws IOException
        {
            try {
                return RetryExecutor.builder()
                    .withRetryLimit(3)
                    .withInitialRetryWaitMillis(500)
                    .withMaxRetryWaitMillis(30 * 1000)
                    .build()
                    .runInterruptible(new Retryable<InputStream>() {
                        @Override
                        public InputStream call() throws InterruptedIOException
                        {
                            log.warn(String.format("FTP read failed. Retrying GET request with %,d bytes offset", offset), closedCause);
                            return startDownload(log, client, path, offset, executor);
                        }

                        @Override
                        public boolean isRetryableException(final Exception exception)
                        {
                            return true;  // TODO
                        }

                        @Override
                        public void onRetry(final Exception exception, final int retryCount, final int retryLimit, final int retryWait)
                                throws RetryGiveupException
                        {
                            final String message = String.format("FTP GET request failed. Retrying %d/%d after %d seconds. Message: %s",
                                    retryCount, retryLimit, retryWait / 1000, exception.getMessage());
                            if (retryCount % 3 == 0) {
                                log.warn(message, exception);
                            }
                            else {
                                log.warn(message);
                            }
                        }

                        @Override
                        public void onGiveup(final Exception firstException, final Exception lastException)
                                throws RetryGiveupException
                        {
                        }
                    });
            }
            catch (final RetryGiveupException ex) {
                final Throwable cause = ex.getCause();
                if (cause instanceof IOException) {
                    throw new UncheckedIOException((IOException) cause);
                }
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                throw new RuntimeException(cause);
            }
            catch (final InterruptedException ex) {
                throw new InterruptedIOException();
            }
        }
    }

    // TODO create single-file InputStreamFileInput utility
    private static class SingleFileProvider
            implements InputStreamFileInput.Provider
    {
        private final Logger log;
        private final FTPClient client;
        private final ExecutorService executor;
        private final String path;
        private boolean opened = false;

        public SingleFileProvider(final Logger log, final PluginTask task, final int taskIndex)
        {
            this.log = log;
            this.client = newFTPClient(log, task);
            this.executor = Executors.newCachedThreadPool(new FormattedThreadFactory());
            this.path = task.getFiles().get(taskIndex);
        }

        @Override
        public InputStreamWithHints openNextWithHints() throws IOException
        {
            if (opened) {
                return null;
            }
            opened = true;

            return new InputStreamWithHints(
                    new ResumableInputStream(
                            startDownload(log, client, path, 0L, executor),
                            new FtpInputStreamReopener(log, client, executor, path)), path
            );
        }

        @Override
        public void close()
        {
            try {
                executor.shutdownNow();
            }
            finally {
                disconnectClient(client);
            }
        }
    }

    public static class FtpFileInput
            extends InputStreamFileInput
            implements TransactionalFileInput
    {
        public FtpFileInput(final Logger log, final PluginTask task, final int taskIndex)
        {
            super(Exec.getBufferAllocator(), new SingleFileProvider(log, task, taskIndex));
        }

        @Override
        public void abort()
        {
        }

        @Override
        public TaskReport commit()
        {
            return CONFIG_MAPPER_FACTORY.newTaskReport();
        }
    }

    private static class FormattedThreadFactory implements ThreadFactory {
        FormattedThreadFactory() {
            this.count = new AtomicLong(0);
        }

        @Override
        public Thread newThread(final Runnable runnable)
        {
            final Thread thread = Executors.defaultThreadFactory().newThread(runnable);
            thread.setName(String.format(Locale.ROOT, "embulk-input-ftp-transfer-%d", this.count.getAndIncrement()));
            thread.setDaemon(true);
            return thread;
        }

        private final AtomicLong count;
    }
}
