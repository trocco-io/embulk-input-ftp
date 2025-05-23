package org.embulk.input.ftp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.embulk.EmbulkTestRuntime;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigException;
import org.embulk.config.ConfigSource;
import org.embulk.config.TaskReport;
import org.embulk.config.TaskSource;
import org.embulk.input.ftp.FtpFileInputPlugin.PluginTask;
import org.embulk.spi.FileInputPlugin;
import org.embulk.spi.FileInputRunner;
import org.embulk.spi.InputPlugin;
import org.embulk.spi.Schema;
import org.embulk.spi.TestPageBuilderReader;
import org.embulk.spi.TestPageBuilderReader.MockPageOutput;
import org.embulk.util.config.Config;
import org.embulk.util.config.ConfigMapper;
import org.embulk.util.config.ConfigMapperFactory;
import org.embulk.util.config.Task;
import org.embulk.util.config.units.SchemaConfig;
import org.embulk.util.ssl.SSLPlugins;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

public class TestFtpFileInputPlugin
{
    private static final ConfigMapperFactory CONFIG_MAPPER_FACTORY = ConfigMapperFactory.builder().addDefaultModules().build();

    private static String FTP_TEST_HOST;
    private static Integer FTP_TEST_PORT;
    private static Integer FTP_TEST_SSL_PORT;
    private static String FTP_TEST_USER;
    private static String FTP_TEST_PASSWORD;
    private static String FTP_TEST_SSL_TRUSTED_CA_CERT_FILE;
    private static String FTP_TEST_SSL_TRUSTED_CA_CERT_DATA;
    private static String FTP_TEST_DIRECTORY;
    private static String FTP_TEST_PATH_PREFIX;

    private FileInputRunner runner;
    private TestPageBuilderReader.MockPageOutput output;
    private final Pattern defaultPathMatchPattern = Pattern.compile(".*");

    /*
     * This test case requires environment variables
     *   FTP_TEST_HOST
     *   FTP_TEST_USER
     *   FTP_TEST_PASSWORD
     *   FTP_TEST_SSL_TRUSTED_CA_CERT_FILE
     */
    @BeforeClass
    public static void initializeConstant()
    {
        final Map<String, String> env = System.getenv();
        FTP_TEST_HOST = env.getOrDefault("FTP_TEST_HOST", "localhost");
        FTP_TEST_PORT = Integer.valueOf(env.getOrDefault("FTP_TEST_PORT", "11021"));
        FTP_TEST_SSL_PORT = Integer.valueOf(env.getOrDefault("FTP_TEST_SSL_PORT", "990"));
        FTP_TEST_USER = env.getOrDefault("FTP_TEST_USER", "scott");
        FTP_TEST_PASSWORD = env.getOrDefault("FTP_TEST_PASSWORD", "tiger");
        FTP_TEST_SSL_TRUSTED_CA_CERT_FILE = env.getOrDefault("FTP_TEST_SSL_TRUSTED_CA_CERT_FILE", "dummy");
        FTP_TEST_SSL_TRUSTED_CA_CERT_DATA = env.getOrDefault("FTP_TEST_SSL_TRUSTED_CA_CERT_DATA", "dummy");

        FTP_TEST_DIRECTORY = getDirectory(env.getOrDefault("FTP_TEST_DIRECTORY", "/unittest/"));
        FTP_TEST_PATH_PREFIX = FTP_TEST_DIRECTORY + "sample_";
    }

    @Rule
    public EmbulkTestRuntime runtime = new EmbulkTestRuntime();
    private FtpFileInputPlugin plugin;

    @Before
    public void createResources()
    {
        plugin = new FtpFileInputPlugin();
        runner = new FileInputRunner(runtime.getInstance(FtpFileInputPlugin.class));
        output = new MockPageOutput();
    }

    @Test(expected = RuntimeException.class) // TODO ConfigException should be thrown
    public void testTransactionWithInvalidHost()
    {
        final ConfigSource config = config().deepCopy()
                .set("host", "non-exists.example.com");

        runner.transaction(config, new Control());
    }

    @Test
    public void testResume()
    {
        final ConfigMapper configMapper = CONFIG_MAPPER_FACTORY.createConfigMapper();
        final PluginTask task = configMapper.map(config(), PluginTask.class);
        task.setSSLConfig(sslConfig(task));
        task.setFiles(Arrays.asList("in/aa/a"));
        final ConfigDiff configDiff = plugin.resume(task.toTaskSource(), 0, new FileInputPlugin.Control()
        {
            @Override
            public List<TaskReport> run(final TaskSource taskSource, final int taskCount)
            {
                return emptyTaskReports(taskCount);
            }
        });
        assertEquals(configDiff.get(String.class, "last_path"), "in/aa/a");
    }

    @Test
    public void testCleanup()
    {
        final ConfigMapper configMapper = CONFIG_MAPPER_FACTORY.createConfigMapper();
        final PluginTask task = configMapper.map(config(), PluginTask.class);
        plugin.cleanup(task.toTaskSource(), 0, Lists.<TaskReport>newArrayList()); // no errors happens
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testListFilesWithNonExistPath() throws Exception
    {
        final ConfigSource config = config().deepCopy()
                .set("path_prefix", "non-exists-path");
        final ConfigMapper configMapper = CONFIG_MAPPER_FACTORY.createConfigMapper();
        final PluginTask task = configMapper.map(config, PluginTask.class);
        plugin.transaction(config, new FileInputPlugin.Control() {
            @Override
            public List<TaskReport> run(final TaskSource taskSource, final int taskCount)
            {
                assertEquals(taskCount, 0);
                return emptyTaskReports(taskCount);
            }
        });

        final Method method = FtpFileInputPlugin.class.getDeclaredMethod("listFiles", Logger.class, PluginTask.class, Pattern.class);
        method.setAccessible(true);
        final Logger logger = LoggerFactory.getLogger(FtpFileInputPlugin.class);
        final List<String> fileList = (List<String>) method.invoke(plugin, logger, task, defaultPathMatchPattern);
        assertEquals(fileList.size(), 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testListFiles() throws Exception
    {
        final List<String> expected = Arrays.asList(
            FTP_TEST_PATH_PREFIX + "01.csv",
            FTP_TEST_PATH_PREFIX + "02.csv"
        );

        final ConfigSource config = config();
        final ConfigMapper configMapper = CONFIG_MAPPER_FACTORY.createConfigMapper();
        final PluginTask task = configMapper.map(config, PluginTask.class);
        final ConfigDiff configDiff = plugin.transaction(config, new FileInputPlugin.Control() {
            @Override
            public List<TaskReport> run(final TaskSource taskSource, final int taskCount)
            {
                assertEquals(taskCount, 2);
                return emptyTaskReports(taskCount);
            }
        });

        final Method method = FtpFileInputPlugin.class.getDeclaredMethod("listFiles", Logger.class, PluginTask.class, Pattern.class);
        method.setAccessible(true);
        final Logger logger = LoggerFactory.getLogger(FtpFileInputPlugin.class);
        final List<String> fileList = (List<String>) method.invoke(plugin, logger, task, defaultPathMatchPattern);

        assertEquals(fileList.get(0), expected.get(0));
        assertEquals(fileList.get(1), expected.get(1));
        assertEquals(configDiff.get(String.class, "last_path"), FTP_TEST_PATH_PREFIX + "02.csv");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testListFileWithEmptyPattern() throws Exception
    {
        final List<String> expected = Arrays.asList(
            FTP_TEST_PATH_PREFIX + "01.csv",
            FTP_TEST_PATH_PREFIX + "02.csv"
        );

        final ConfigSource config = config();
        config.set("path_match_pattern", "");
        final ConfigMapper configMapper = CONFIG_MAPPER_FACTORY.createConfigMapper();
        final PluginTask task = configMapper.map(config, PluginTask.class);
        final ConfigDiff configDiff = plugin.transaction(config, new FileInputPlugin.Control() {
            @Override
            public List<TaskReport> run(final TaskSource taskSource, final int taskCount)
            {
                assertEquals(taskCount, 2);
                return emptyTaskReports(taskCount);
            }
        });

        final Method method = FtpFileInputPlugin.class.getDeclaredMethod("listFiles", Logger.class, PluginTask.class, Pattern.class);
        method.setAccessible(true);
        final Logger logger = LoggerFactory.getLogger(FtpFileInputPlugin.class);
        final List<String> fileList = (List<String>) method.invoke(plugin, logger, task, defaultPathMatchPattern);

        assertEquals(fileList.get(0), expected.get(0));
        assertEquals(fileList.get(1), expected.get(1));
        assertEquals(configDiff.get(String.class, "last_path"), FTP_TEST_PATH_PREFIX + "02.csv");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testListFileWithSpacesPattern() throws Exception
    {
        final List<String> expected = Arrays.asList(
            FTP_TEST_PATH_PREFIX + "01.csv",
            FTP_TEST_PATH_PREFIX + "02.csv"
        );

        final ConfigSource config = config();
        config.set("path_match_pattern", "    ");
        final ConfigMapper configMapper = CONFIG_MAPPER_FACTORY.createConfigMapper();
        final PluginTask task = configMapper.map(config, PluginTask.class);
        final ConfigDiff configDiff = plugin.transaction(config, new FileInputPlugin.Control() {
            @Override
            public List<TaskReport> run(final TaskSource taskSource, final int taskCount)
            {
                assertEquals(taskCount, 2);
                return emptyTaskReports(taskCount);
            }
        });

        final Method method = FtpFileInputPlugin.class.getDeclaredMethod("listFiles", Logger.class, PluginTask.class, Pattern.class);
        method.setAccessible(true);
        final Logger logger = LoggerFactory.getLogger(FtpFileInputPlugin.class);
        final List<String> fileList = (List<String>) method.invoke(plugin, logger, task, defaultPathMatchPattern);

        assertEquals(fileList.get(0), expected.get(0));
        assertEquals(fileList.get(1), expected.get(1));
        assertEquals(configDiff.get(String.class, "last_path"), FTP_TEST_PATH_PREFIX + "02.csv");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testListFilesWithPathMatcher() throws Exception
    {
        final String pattern = FTP_TEST_PATH_PREFIX + "02";
        final Pattern pathMatchPattern = Pattern.compile(pattern);
        final List<String> expected = Arrays.asList(
            FTP_TEST_PATH_PREFIX + "02.csv"
        );

        final ConfigSource config = config();
        config.set("path_match_pattern", pattern);
        final ConfigMapper configMapper = CONFIG_MAPPER_FACTORY.createConfigMapper();
        final PluginTask task = configMapper.map(config, PluginTask.class);
        final ConfigDiff configDiff = plugin.transaction(config, new FileInputPlugin.Control() {
            @Override
            public List<TaskReport> run(final TaskSource taskSource, final int taskCount)
            {
                assertEquals(taskCount, 1);
                return emptyTaskReports(taskCount);
            }
        });

        final Method method = FtpFileInputPlugin.class.getDeclaredMethod("listFiles", Logger.class, PluginTask.class, Pattern.class);
        method.setAccessible(true);
        final Logger logger = LoggerFactory.getLogger(FtpFileInputPlugin.class);
        final List<String> fileList = (List<String>) method.invoke(plugin, logger, task, pathMatchPattern);

        assertEquals(fileList.get(0), expected.get(0));
        assertEquals(configDiff.get(String.class, "last_path"), FTP_TEST_PATH_PREFIX + "02.csv");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testListFilesWithNonExistPathMatcher() throws Exception
    {
        final String pattern = "non_exist_file_matcher";
        final Pattern pathMatchPattern = Pattern.compile(pattern);

        final ConfigSource config = config();
        config.set("path_match_pattern", pattern);
        final ConfigMapper configMapper = CONFIG_MAPPER_FACTORY.createConfigMapper();
        final PluginTask task = configMapper.map(config, PluginTask.class);
        final ConfigDiff configDiff = plugin.transaction(config, new FileInputPlugin.Control() {
            @Override
            public List<TaskReport> run(final TaskSource taskSource, final int taskCount)
            {
                assertEquals(taskCount, 0);
                return emptyTaskReports(taskCount);
            }
        });

        final Method method = FtpFileInputPlugin.class.getDeclaredMethod("listFiles", Logger.class, PluginTask.class, Pattern.class);
        method.setAccessible(true);
        final Logger logger = LoggerFactory.getLogger(FtpFileInputPlugin.class);
        final List<String> fileList = (List<String>) method.invoke(plugin, logger, task, pathMatchPattern);

        assertEquals(fileList.size(), 0);
        assertEquals(configDiff.get(String.class, "last_path"), "");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testListFilesWithNotExactPathMatcher() throws Exception
    {
        final String pattern = "\\b" + FTP_TEST_PATH_PREFIX + "\\b";
        final Pattern pathMatchPattern = Pattern.compile(pattern);

        final ConfigSource config = config();
        config.set("path_match_pattern", pattern);
        final ConfigMapper configMapper = CONFIG_MAPPER_FACTORY.createConfigMapper();
        final PluginTask task = configMapper.map(config, PluginTask.class);
        final ConfigDiff configDiff = plugin.transaction(config, new FileInputPlugin.Control() {
            @Override
            public List<TaskReport> run(final TaskSource taskSource, final int taskCount)
            {
                assertEquals(taskCount, 0);
                return emptyTaskReports(taskCount);
            }
        });

        final Method method = FtpFileInputPlugin.class.getDeclaredMethod("listFiles", Logger.class, PluginTask.class, Pattern.class);
        method.setAccessible(true);
        final Logger logger = LoggerFactory.getLogger(FtpFileInputPlugin.class);
        final List<String> fileList = (List<String>) method.invoke(plugin, logger, task, pathMatchPattern);

        assertEquals(fileList.size(), 0);
        assertEquals(configDiff.get(String.class, "last_path"), "");
    }

    @Test
    @org.junit.Ignore("Skipping test that requires CSV parser")
    public void testListFilesByPrefixIncrementalFalse()
    {
        final ConfigSource config = configLegacy()
                .deepCopy()
                .set("incremental", false);

        final ConfigDiff configDiff = runner.transaction(config, new Control());

        Assert.assertEquals(configDiff.toString(), "{}");
    }

    @Test
    @SuppressWarnings("unchecked")
    @org.junit.Ignore("Skipping test that requires CSV parser")
    public void testFtpFileInputByOpen() throws Exception
    {
        final ConfigSource configLegacy = configLegacy();

        final ConfigSource config = config();
        final ConfigMapper configMapper = CONFIG_MAPPER_FACTORY.createConfigMapper();
        final PluginTask task = configMapper.map(config, PluginTask.class);

        runner.transaction(configLegacy, new Control());

        final Method method = FtpFileInputPlugin.class.getDeclaredMethod("listFiles", Logger.class, PluginTask.class, Pattern.class);
        method.setAccessible(true);
        final Logger logger = LoggerFactory.getLogger(FtpFileInputPlugin.class);
        final List<String> fileList = (List<String>) method.invoke(plugin, logger, task, defaultPathMatchPattern);
        task.setFiles(fileList);

        assertRecords(config, output);
    }

@Test
@SuppressWarnings("unchecked")
public void testListFilesStopWhenFileNotFound_Ftp() throws Exception
{
    final String pattern = "\\.xml$";
    final Pattern pathMatchPattern = Pattern.compile(pattern);

    final ConfigSource config = config();
    config.set("path_match_pattern", pattern);
    config.set("stop_when_file_not_found", true);
    config.set("path_prefix", FTP_TEST_PATH_PREFIX + "sample_01.csv");

    final ConfigMapper configMapper = CONFIG_MAPPER_FACTORY.createConfigMapper();
    final PluginTask task = configMapper.map(config, PluginTask.class);

    final Method method = FtpFileInputPlugin.class.getDeclaredMethod("listFiles", Logger.class, PluginTask.class, Pattern.class);
    method.setAccessible(true);
    final Logger logger = LoggerFactory.getLogger(FtpFileInputPlugin.class);

    try {
        method.invoke(plugin, logger, task, pathMatchPattern);
        fail("Expected ConfigException to be thrown");
    } catch (InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause instanceof ConfigException) {
            String expected = "No file is found. \"stop_when_file_not_found\" option is \"true\".";
            assertEquals(expected, cause.getMessage());
        } else {
            throw e;
        }
    }
}


    private static List<TaskReport> emptyTaskReports(final int taskCount)
    {
        final ImmutableList.Builder<TaskReport> reports = new ImmutableList.Builder<>();
        for (int i = 0; i < taskCount; i++) {
            reports.add(CONFIG_MAPPER_FACTORY.newTaskReport());
        }
        return reports.build();
    }

    private class Control
            implements InputPlugin.Control
    {
        @Override
        public List<TaskReport> run(final TaskSource taskSource, final Schema schema, final int taskCount)
        {
            final List<TaskReport> reports = new ArrayList<>();
            for (int i = 0; i < taskCount; i++) {
                reports.add(runner.run(taskSource, schema, i, output));
            }
            return reports;
        }
    }

    private ConfigSource config()
    {
        return CONFIG_MAPPER_FACTORY.newConfigSource()
                .set("host", FTP_TEST_HOST)
                .set("port", FTP_TEST_PORT)
                .set("user", FTP_TEST_USER)
                .set("password", FTP_TEST_PASSWORD)
                .set("path_prefix", FTP_TEST_PATH_PREFIX)
                .set("last_path", "")
                .set("file_ext", ".csv")
                .set("max_connection_retry", 3)
                .set("ssl", false)
                .set("ssl_verify", false)
                .set("ssl_verify_hostname", false)
                .set("ssl_trusted_ca_cert_data", FTP_TEST_SSL_TRUSTED_CA_CERT_DATA)
                .set("parser", parserConfig(schemaConfig()));
    }

    private ConfigSource configLegacy()
    {
        return runtime.getExec().newConfigSource()
                .set("host", FTP_TEST_HOST)
                .set("port", FTP_TEST_PORT)
                .set("user", FTP_TEST_USER)
                .set("password", FTP_TEST_PASSWORD)
                .set("path_prefix", FTP_TEST_PATH_PREFIX)
                .set("last_path", "")
                .set("file_ext", ".csv")
                .set("max_connection_retry", 3)
                .set("ssl", false)
                .set("ssl_verify", false)
                .set("ssl_verify_hostname", false)
                .set("ssl_trusted_ca_cert_data", FTP_TEST_SSL_TRUSTED_CA_CERT_DATA)
                .set("parser", parserConfig(schemaConfig()));
    }

    private ImmutableMap<String, Object> parserConfig(final ImmutableList<Object> schemaConfig)
    {
        final ImmutableMap.Builder<String, Object> builder = new ImmutableMap.Builder<>();
        builder.put("type", "csv");
        builder.put("newline", "CRLF");
        builder.put("delimiter", ",");
        builder.put("quote", "\"");
        builder.put("escape", "\"");
        builder.put("trim_if_not_quoted", false);
        builder.put("skip_header_lines", 1);
        builder.put("allow_extra_columns", false);
        builder.put("allow_optional_columns", false);
        builder.put("columns", schemaConfig);
        return builder.build();
    }

    private ImmutableList<Object> schemaConfig()
    {
        final ImmutableList.Builder<Object> builder = new ImmutableList.Builder<>();
        builder.add(ImmutableMap.of("name", "id", "type", "long"));
        builder.add(ImmutableMap.of("name", "account", "type", "long"));
        builder.add(ImmutableMap.of("name", "time", "type", "timestamp", "format", "%Y-%m-%d %H:%M:%S"));
        builder.add(ImmutableMap.of("name", "purchase", "type", "timestamp", "format", "%Y%m%d"));
        builder.add(ImmutableMap.of("name", "comment", "type", "string"));
        return builder.build();
    }

    public SSLPlugins.SSLPluginConfig sslConfig(final PluginTask task)
    {
        return SSLPlugins.configure(task);
    }

    private void assertRecords(final ConfigSource config, final MockPageOutput output)
    {
        final List<Object[]> records = getRecords(config, output);
        assertEquals(records.size(), 8);
        {
            final Object[] record = records.get(0);
            assertEquals((long) record[0], 1L);
            assertEquals((long) record[1], 32864L);
            assertEquals(record[2].toString(), "2015-01-27 19:23:49 UTC");
            assertEquals(record[3].toString(), "2015-01-27 00:00:00 UTC");
            assertEquals(record[4].toString(), "embulk");
        }

        {
            final Object[] record = records.get(1);
            assertEquals((long) record[0], 2L);
            assertEquals((long) record[1], 14824L);
            assertEquals(record[2].toString(), "2015-01-27 19:01:23 UTC");
            assertEquals(record[3].toString(), "2015-01-27 00:00:00 UTC");
            assertEquals(record[4].toString(), "embulk jruby");
        }
    }

    private List<Object[]> getRecords(final ConfigSource config, final MockPageOutput output)
    {
        final ConfigMapper configMapper = CONFIG_MAPPER_FACTORY.createConfigMapper();
        final Schema schema = configMapper.map(config.getNested("parser"), SchemaConfigTask.class).getSchemaConfig().toSchema();
        return Pages.toObjects(schema, output.pages);
    }

    private static String getDirectory(String dir)
    {
        if (dir != null && !dir.endsWith("/")) {
            dir = dir + "/";
        }
        if (dir.startsWith("/")) {
            dir = dir.replaceFirst("/", "");
        }
        return dir;
    }

    private interface SchemaConfigTask extends Task {
        @Config("columns")
        SchemaConfig getSchemaConfig();
    }
}
