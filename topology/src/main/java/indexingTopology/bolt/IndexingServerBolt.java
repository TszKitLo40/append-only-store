package indexingTopology.bolt;

import indexingTopology.bloom.DataChunkBloomFilters;
import indexingTopology.bolt.metrics.LocationInfo;
import indexingTopology.common.*;
import indexingTopology.common.data.DataTuple;
import indexingTopology.config.TopologyConfig;
import indexingTopology.common.data.TrackedDataTuple;
import indexingTopology.index.Indexer;
import indexingTopology.index.IndexerBuilder;
import org.apache.storm.metric.internal.RateTracker;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import indexingTopology.common.data.DataSchema;
import indexingTopology.streams.Streams;
import javafx.util.Pair;
import org.apache.storm.tuple.Values;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Created by acelzj on 11/15/16.
 */
public class IndexingServerBolt extends BaseRichBolt implements Observer {
    private final DataSchema schema;

    private OutputCollector collector;

    private IndexerBuilder indexerBuilder;

    private Indexer indexer;

    private LinkedBlockingQueue<DataTuple> inputQueue;

    private ArrayBlockingQueue<SubQuery> queryPendingQueue;

    private Observable observable;

    private int numTuples;

    private long start;

    private RateTracker rateTracker;

    private List<String> bloomFilterColumns;

    private Thread locationReportingThread;

    TopologyConfig config;

    public IndexingServerBolt(DataSchema schema, List<String> bloomFilterColumns, TopologyConfig config) {
        this.schema = schema;
        this.bloomFilterColumns = bloomFilterColumns;
        this.config = config;
    }

    public IndexingServerBolt(DataSchema schema, TopologyConfig conf) {
        this(schema, new ArrayList<>(), conf);
    }

    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {


        collector = outputCollector;

        this.inputQueue = new LinkedBlockingQueue<>();

        this.queryPendingQueue = new ArrayBlockingQueue<>(1024);

        indexerBuilder = new IndexerBuilder(config);

        indexer = indexerBuilder
                .setTaskId(topologyContext.getThisTaskId())
                .setDataSchema(schema)
                .setInputQueue(inputQueue)
                .setQueryPendingQueue(queryPendingQueue)
                .setBloomFilterIndexedColumns(bloomFilterColumns)
                .getIndexer();

        this.observable = indexer;
        observable.addObserver(this);
        start = System.currentTimeMillis();
        numTuples = 0;

        rateTracker = new RateTracker(5 * 1000, 5);

        locationReportingThread = new Thread(() -> {
            while (true) {
                String hostName = "unknown";
                try {
                    hostName = InetAddress.getLocalHost().getHostName();
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                }
                LocationInfo info = new LocationInfo(LocationInfo.Type.Ingestion, topologyContext.getThisTaskId(), hostName);
                outputCollector.emit(Streams.LocationInfoUpdateStream, new Values(info));

                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        locationReportingThread.start();
    }

    @Override
    public void cleanup() {
        super.cleanup();
        indexer.close();
        locationReportingThread.interrupt();
    }

    public void execute(Tuple tuple) {
        if (tuple.getSourceStreamId().equals(Streams.IndexStream)) {
//            DataTuple dataTuple = (DataTuple) tuple.getValueByField("tuple");
            byte[] dataTupleBytes = (byte[]) tuple.getValueByField("tuple");
            DataTuple dataTuple = schema.deserializeToDataTuple(dataTupleBytes);

            Long tupleId = tuple.getLongByField("tupleId");

            int taskId = tuple.getIntegerByField("taskId");

            rateTracker.notify(1);

            try {

                if (tupleId % config.EMIT_NUM == 0) {
                    // this tuple will be acked by the indexer via update function.
                    dataTuple = new TrackedDataTuple(tupleId, taskId, dataTuple);
                }

                while (!inputQueue.offer(dataTuple, 5, TimeUnit.SECONDS)) {
                    System.out.println("Failed to offer a data tuple to the input queue. Will retry...");
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
//            if (numTuples == 0) {
//                start = System.currentTimeMillis();
//            }
//                ++numTuples;
//                if (numTuples >= 1200000) {
//                    System.out.println("Throughput: " + (numTuples * 1000 / ((System.currentTimeMillis() - start)*1.0)));
//                    numTuples = 0;
//                    start = System.currentTimeMillis();
//                }
//                collector.ack(tuple);
//                System.out.println("tuple id " + tupleId);
//                if (tupleId % config.EMIT_NUM == 0) {
////                    System.out.println("tuple id " + tupleId + " has been acked!!!");
////                    System.out.println(inputQueue.size());
//                    collector.emitDirect(taskId, Streams.AckStream, new Values(tupleId));
//                }
            }
        } else if (tuple.getSourceStreamId().equals(Streams.BPlusTreeQueryStream)){
            SubQuery subQuery = (SubQuery) tuple.getValueByField("subquery");
            try {
//                System.out.println("Insertion Server: Received a subquery!");
                queryPendingQueue.put(subQuery);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } else if (tuple.getSourceStreamId().equals(Streams.TreeCleanStream)) {
            TimeDomain timeDomain = (TimeDomain) tuple.getValueByField("timeDomain");
            KeyDomain keyDomain = (KeyDomain) tuple.getValueByField("keyDomain");
            indexer.cleanTree(new Domain(keyDomain, timeDomain));
        } else if (tuple.getSourceStreamId().equals(Streams.ThroughputRequestStream)) {
            collector.emit(Streams.ThroughputReportStream, new Values(rateTracker.reportRate()));
        }
    }

    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
//        outputFieldsDeclarer.declareStream(Streams.FileInformationUpdateStream,
//                new Fields("fileName", "keyDomain", "timeDomain"));

        outputFieldsDeclarer.declareStream(Streams.FileInformationUpdateStream,
                new Fields("fileName", "keyDomain", "timeDomain", "tupleCount", "bloomFilters"));

        outputFieldsDeclarer.declareStream(Streams.BPlusTreeQueryStream,
                new Fields("queryId", "serializedTuples"));

        outputFieldsDeclarer.declareStream(Streams.TimestampUpdateStream,
                new Fields("timeDomain", "keyDomain"));

        outputFieldsDeclarer.declareStream(Streams.AckStream, new Fields("tupleId"));

        outputFieldsDeclarer.declareStream(Streams.ThroughputReportStream, new Fields("throughput"));

        outputFieldsDeclarer.declareStream(Streams.LocationInfoUpdateStream, new Fields("info"));

    }

    @Override
    public void update(Observable o, Object arg) {
        if (o instanceof Indexer) {

            Indexer indexer = (Indexer)o;

            // handle file information upadtes
            FileInformation fileInformation = indexer.tryToGetFileInformation();
            while (fileInformation != null) {
                String fileName = fileInformation.getFileName();
                Domain domain = fileInformation.getDomain();
                KeyDomain keyDomain = new KeyDomain(domain.getLowerBound(), domain.getUpperBound());
                TimeDomain timeDomain = new TimeDomain(domain.getStartTimestamp(), domain.getEndTimestamp());
                Long numTuples = fileInformation.getNumberOfRecords();
                DataChunkBloomFilters bloomFilters = fileInformation.getBloomFilters();

//                System.out.println("File information is sent from insertion servers");
                collector.emit(Streams.FileInformationUpdateStream, new Values(fileName, keyDomain, timeDomain,
                        numTuples, bloomFilters));

                collector.emit(Streams.TimestampUpdateStream, new Values(timeDomain, keyDomain));
                fileInformation = indexer.tryToGetFileInformation();
            }

            // handle query results
            Pair pair = indexer.tryToGetQueryResult();
            while (pair != null) {
                SubQuery subQuery = (SubQuery) pair.getKey();
                List<byte[]> queryResults = (List<byte[]>) pair.getValue();
                System.out.println(String.format("query result on B+tree for %d will be emitted!", subQuery.queryId));
                collector.emit(Streams.BPlusTreeQueryStream, new Values(subQuery, queryResults));
                System.out.println(String.format("query result on B+tree for %d has been emitted!", subQuery.queryId));
                pair = indexer.tryToGetQueryResult();
            }

            // handle tuple ack
            TrackedDataTuple dataTuple = indexer.tryToGetTrackedDataTuple();
            while (dataTuple != null) {
                collector.emitDirect(dataTuple.sourceTaskId, Streams.AckStream, new Values(dataTuple.tupleId));
                dataTuple = indexer.tryToGetTrackedDataTuple();
            }


//            try {
//                String s = (String) arg;
//                if (s.equals("information update")) {
//                    FileInformation fileInformation = ((Indexer) o).getFileInformation();
//                    String fileName = fileInformation.getFileName();
//                    Domain domain = fileInformation.getDomain();
//                    KeyDomain keyDomain = new KeyDomain(domain.getLowerBound(), domain.getUpperBound());
//                    TimeDomain timeDomain = new TimeDomain(domain.getStartTimestamp(), domain.getEndTimestamp());
//                    Long numTuples = fileInformation.getNumberOfRecords();
//                    DataChunkBloomFilters bloomFilters = fileInformation.getBloomFilters();
//
////                System.out.println("File information is sent from insertion servers");
//                    collector.emit(Streams.FileInformationUpdateStream, new Values(fileName, keyDomain, timeDomain,
//                            numTuples, bloomFilters));
//
//                    collector.emit(Streams.TimestampUpdateStream, new Values(timeDomain, keyDomain));
//
//
//                } else if (s.equals("query result")) {
//                    Pair pair = ((Indexer) o).getQueryResult();
//                    SubQuery subQuery = (SubQuery) pair.getKey();
//                    List<byte[]> queryResults = (List<byte[]>) pair.getValue();
////                List<byte[]> serializedTuples = new ArrayList<>();
////                for(DataTuple dataTuple: queryResults) {
////                    serializedTuples.add(schema.serializeTuple(dataTuple));
////                }
//                    System.out.println(String.format("query result on B+tree for %d will be emitted!", subQuery.queryId));
//                    collector.emit(Streams.BPlusTreeQueryStream, new Values(subQuery, queryResults));
//                    System.out.println(String.format("query result on B+tree for %d has been emitted!", subQuery.queryId));
//                } else if (s.equals("ack")) {
//                    try {
//                        TrackedDataTuple dataTuple = ((Indexer) o).getTrackedDataTuple();
//                        collector.emitDirect(dataTuple.sourceTaskId, Streams.AckStream, new Values(dataTuple.tupleId));
//                    } catch (InterruptedException e) {
//                        e.printStackTrace();
//                    }
//                }
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
        }
    }
}
