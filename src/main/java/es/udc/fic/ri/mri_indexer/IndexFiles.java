package es.udc.fic.ri.mri_indexer;

import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

public class IndexFiles {

    private final static String CONFIG_FILE = "config.properties";
    private static Map<String,String> properties;

    private static boolean update = false;
    private static String[] onlyFiles = null;
    private static int numTopLines = -1;
    private static int numBottomLines = -1;

    private IndexFiles() {}

    public static class WorkerThread implements Runnable {

        private final Path folder;
        private final IndexWriter writer;
        private final boolean close;

        public WorkerThread(String folder, IndexWriter writer, boolean close) {
            this.folder = Paths.get(folder);
            this.writer = writer;
            this.close = close;
        }

        @Override
        public void run() {
            try {
                indexDocs(writer, folder);
                writer.commit();
                if (close) {
                    writer.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        String usage = "java es.udc.fic.ri.mri_indexer.IndexFiles"
                + " [-index INDEX_PATH] [-openmode [create, append, create_or_append]] [-update]"
                + " [-numThreads N] [-onlyFiles] [-partialIndexes]";

        String indexPath = null;
        OpenMode openMode = null;
        boolean threads = false;
        int numThreads = -1;
        String[] partialIndexesPath = null;
        Directory[] partialDirectories = null;

        for(int i=0; i<args.length; i++) {
            if ("-index".equals(args[i])) {
                indexPath = args[++i];
            } else if ("-update".equals(args[i])) {
                update = true;
            } else if ("-openmode".equals(args[i])) {
                String mode = args[++i];
                switch (mode) {
                    case "create":
                        openMode = OpenMode.CREATE; break;
                    case "append":
                        openMode = OpenMode.APPEND; break;
                    case "create_or_append":
                        openMode = OpenMode.CREATE_OR_APPEND; break;
                }
            } else if ("-numThreads".equals(args[i])) {
                threads = true;
                numThreads = Integer.parseInt(args[++i]);
            } else if ("-partialIndexes".equals(args[i])) {
                partialIndexesPath = getProperty("partialIndexes").split(" ");
            } else if ("-onlyFiles".equals(args[i])) {
                onlyFiles = getProperty("onlyFiles").split(" ");
            }
        }

        if (indexPath == null || openMode == null) {
            System.err.println(usage);
            System.exit(-1);
        }

        String[] docsPath = getProperty("docs").split(" ");
        if (docsPath == null) {
            System.err.println("docs not specified");
            System.exit(-1);
        } else if (partialIndexesPath != null && docsPath.length != partialIndexesPath.length) {
            System.err.println("a partial index must be given for each doc");
            System.exit(-1);
        } else if (partialIndexesPath != null){
            partialDirectories = new Directory[partialIndexesPath.length];
        }

        if (!threads) {
            numThreads = Math.min(Runtime.getRuntime().availableProcessors(), docsPath.length);
        } else if (numThreads < 1) {
            System.err.println("numThreads must be greater than 0: " + numThreads);
            System.exit(-1);
        } else {
            numThreads = Math.min(Runtime.getRuntime().availableProcessors(), numThreads);
            numThreads = Math.min(numThreads, docsPath.length);
        }
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        String top = getProperty("onlyTopLines");
        if (top != null) {
            numTopLines = Integer.parseInt(top);
            if (numTopLines < 0) {
                System.err.println("onlyTopLines must be positive");
                System.exit(-1);
            }
        }

        String bottom = getProperty("onlyBottomLines");
        if (bottom != null) {
            numBottomLines = Integer.parseInt(bottom);
            if (numBottomLines < 0) {
                System.err.println("onlyBottomLines must be positive");
                System.exit(-1);
            }
        }

        Date start = new Date();
        try {
            System.out.println("Indexing to directory '" + indexPath + "'...");
            IndexWriter writer = createWriter(indexPath, new StandardAnalyzer(), openMode);
            Directory dir = writer.getDirectory();

            for (int i=0; i<docsPath.length; i++) {
                if (!Files.isReadable(Paths.get(docsPath[i]))) {
                    System.out.println("Document directory '" + docsPath[i] + "' does not exist or is not readable, please check the path");
                    continue;
                }

                // Run each doc in a thread
                IndexWriter iw = writer;
                if (partialIndexesPath != null) {
                    iw = createWriter(partialIndexesPath[i], new StandardAnalyzer(), OpenMode.CREATE);
                    partialDirectories[i] = iw.getDirectory();
                }
                final Runnable worker = new WorkerThread(docsPath[i], iw, partialIndexesPath != null);
                executor.execute(worker);

            }

            executor.shutdown();

            try {
                executor.awaitTermination(1, TimeUnit.HOURS);
                System.out.println("Finished all threads");
            } catch (final InterruptedException e) {
                e.printStackTrace();
                System.exit(-2);
            }

            // Merge partial indexes
            if (partialDirectories != null) {
                writer.addIndexes(partialDirectories);
                for (Directory partialDir : partialDirectories) {
                    partialDir.close();
                }
            }

            writer.close();
            dir.close();

            Date end = new Date();
            System.out.println(end.getTime() - start.getTime() + " total milliseconds");

        } catch (IOException e) {
            e.printStackTrace();
            System.out.println(" caught a " + e.getClass() +
                    "\n with message: " + e.getMessage());
        }

    }

    static void indexDocs(final IndexWriter writer, Path path) throws IOException {
        if (Files.isDirectory(path)) {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        if (onlyFiles == null) {
                            indexDoc(writer, file, attrs.lastModifiedTime().toMillis());
                        } else {
                            for (String extension : onlyFiles) {
                                if (file.toString().endsWith(extension)) {
                                    indexDoc(writer, file, attrs.lastModifiedTime().toMillis());
                                }
                            }
                        }
                    } catch (IOException e) {
                        // don't index files that can't be read.
                    }
                    return FileVisitResult.CONTINUE;
                }

            });
        } else {
            if (onlyFiles == null) {
                indexDoc(writer, path, Files.getLastModifiedTime(path).toMillis());
            } else {
                for (String extension : onlyFiles) {
                    if (path.endsWith(extension)) {
                        indexDoc(writer, path, Files.getLastModifiedTime(path).toMillis());
                        break;
                    }
                }
            }
        }
    }

    static void indexDoc(IndexWriter writer, Path file, long lastModified) throws IOException {
        try (InputStream stream = Files.newInputStream(file)) {
            Document doc = new Document();

            // Add the path of the file as a field named "path".  Use a
            // field that is indexed (i.e. searchable), but don't tokenize
            // the field into separate words and don't index term frequency
            // or positional information:
            Field pathField = new StringField("path", file.toString(), Field.Store.YES);
            doc.add(pathField);

            // Add the last modified date of the file a field named "modified".
            // Use a LongPoint that is indexed (i.e. efficiently filterable with
            // PointRangeQuery).  This indexes to milli-second resolution, which
            // is often too fine.  You could instead create a number based on
            // year/month/day/hour/minutes/seconds, down the resolution you require.
            // For example the long value 2011021714 would mean
            // February 17, 2011, 2-3 PM.
            doc.add(new LongPoint("modified", lastModified));

            // Add the contents of the file to a field named "contents".  Specify a Reader,
            // so that the text of the file is tokenized and indexed, but not stored.
            // Note that FileReader expects the file to be in UTF-8 encoding.
            // If that's not the case searching for special characters will fail.
            String content;
            if (numTopLines == -1 && numBottomLines == -1) {
                content = new BufferedReader(new InputStreamReader(stream)).lines().collect(Collectors.joining("\n"));
            } else {
                content = getLines(stream, numTopLines, numBottomLines).stream().collect(Collectors.joining("\n"));
            }
            doc.add(new TextField("contents", content, Field.Store.YES));

            String hostname;
            try {
                hostname = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                hostname = "unknown";
            }

            Field hostnameField = new StringField("hostname", hostname, Field.Store.YES);
            doc.add(hostnameField);

            Field threadField = new StringField("thread", Thread.currentThread().getName(), Field.Store.YES);
            doc.add(threadField);

            BasicFileAttributes attr = Files.readAttributes(file, BasicFileAttributes.class);

            Field sizeField = new FloatPoint("sizeKb", (float)attr.size()/1024);
            doc.add(sizeField);

            FileTime creationTime = attr.creationTime();
            Field creationTimeField = new StringField("creationTime", creationTime.toString(), Field.Store.YES);
            doc.add(creationTimeField);

            FileTime lastAccessTime = attr.lastAccessTime();
            Field lastAccessTimeField = new StringField("lastAccessTime", lastAccessTime.toString(), Field.Store.YES);
            doc.add(lastAccessTimeField);

            FileTime lastModifiedTime = attr.lastModifiedTime();
            Field lastModifiedTimeField = new StringField("lastModifiedTime", lastModifiedTime.toString(), Field.Store.YES);
            doc.add(lastModifiedTimeField);

            Date creationTimeLucene = new Date(creationTime.toMillis());
            Field creationTimeLuceneField = new StringField("creationTimeLucene", DateTools.dateToString(creationTimeLucene, DateTools.Resolution.MILLISECOND), Field.Store.YES);
            doc.add(creationTimeLuceneField);

            Date lastAccessTimeLucene = new Date(lastAccessTime.toMillis());
            Field lastAccessTimeLuceneField = new StringField("lastAccessTimeLucene", DateTools.dateToString(lastAccessTimeLucene, DateTools.Resolution.MILLISECOND), Field.Store.YES);
            doc.add(lastAccessTimeLuceneField);

            Date lastModifiedTimeLucene = new Date(lastModifiedTime.toMillis());
            Field lastModifiedTimeLuceneField = new StringField("lastModifiedTimeLucene", DateTools.dateToString(lastModifiedTimeLucene, DateTools.Resolution.MILLISECOND), Field.Store.YES);
            doc.add(lastModifiedTimeLuceneField);

            if (!update || writer.getConfig().getOpenMode() == OpenMode.CREATE) {
                System.out.println("[" + Thread.currentThread().getName() + "] adding " + file + " to " + writer.getDirectory().toString().split(" ")[0]);
                writer.addDocument(doc);
            } else {
                System.out.println("[" + Thread.currentThread().getName() + "] updating " + file + " to " + writer.getDirectory().toString().split(" ")[0]);
                writer.updateDocument(new Term("path", file.toString()), doc);
            }
        }
    }

    private static String getProperty(String name) {
        if (properties == null) {
            ClassLoader classLoader = IndexFiles.class.getClassLoader();
            InputStream inputStream = classLoader.getResourceAsStream(CONFIG_FILE);
            Properties properties = new Properties();
            try {
                properties.load(inputStream);
                inputStream.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            IndexFiles.properties = (Map<String, String>) new HashMap(properties);
        }
        return properties.get(name);
    }

    private static IndexWriter createWriter(String path, Analyzer analyzer, OpenMode openMode) throws IOException {
        Directory dir = FSDirectory.open(Paths.get(path));
        IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
        iwc.setOpenMode(openMode);
        return new IndexWriter(dir, iwc);
    }

    private static List<String> getLines(InputStream stream, int start, int end) {
        List<String> first = new LinkedList<>();
        List<String> last = new LinkedList<>();

        try (BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            int i = 0;
            String line;
            while ((line = in.readLine()) != null) {
                if (i < start) {
                    first.add(line);
                    i++;
                } else if (end != -1){
                    last.add(line);
                }
            }

            if (last.size() > 0) {
                last = last.subList(Math.max(0, last.size()-end), last.size());
            }

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

        first.addAll(last);
        return first;
    }

}


