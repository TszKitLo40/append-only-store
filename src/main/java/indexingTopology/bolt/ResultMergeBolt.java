package indexingTopology.bolt;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import indexingTopology.DataSchema;
import indexingTopology.NormalDistributionIndexingAndRangeQueryTopology;
import indexingTopology.NormalDistributionIndexingTopology;
import indexingTopology.Streams.Streams;
import indexingTopology.util.DeserializationHelper;
import indexingTopology.util.FileScanMetrics;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by acelzj on 11/17/16.
 */
public class ResultMergeBolt extends BaseRichBolt {

    Map<Long, Integer> queryIdToNumberOfTuples;

    ConcurrentHashMap<Long, Integer> queryIdToCounter;

    Map<Long, Integer> queryIdToNumberOfFilesToScan;

    Map<Long, Integer> queryIdToNumberOfTasksToSearch;

    Map<Long, FileScanMetrics> queryIdToFileScanMetrics;

    DataSchema schema;

    OutputCollector collector;

    private int counter;

    public ResultMergeBolt(DataSchema schema) {
        this.schema = schema;
        counter = 0;
    }

    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        queryIdToNumberOfTuples = new HashMap<Long, Integer>();
//        queryIdToCounter = new HashMap<Long, Integer>();
        queryIdToCounter = new ConcurrentHashMap<Long, Integer>();
        queryIdToNumberOfFilesToScan = new HashMap<Long, Integer>();
        queryIdToNumberOfTasksToSearch = new HashMap<Long, Integer>();

        queryIdToFileScanMetrics = new HashMap<Long, FileScanMetrics>();

        collector = outputCollector;
    }

    public void execute(Tuple tuple) {
        if (tuple.getSourceStreamId()
                .equals(Streams.BPlusTreeQueryInformationStream)) {
            int numberOfTasksToSearch = tuple.getInteger(1);

            Long queryId = tuple.getLong(0);

            queryIdToNumberOfTasksToSearch.put(queryId, numberOfTasksToSearch);

            if (isQueryFinshed(queryId)) {
                sendNewQueryPermit(queryId);
                removeQueryIdFromMappings(queryId);
            }
        } else if (tuple.getSourceStreamId()
                .equals(Streams.FileSystemQueryInformationStream)) {
            int numberOfFilesToScan = tuple.getInteger(1);

            Long queryId = tuple.getLong(0);

            queryIdToNumberOfFilesToScan.put(queryId, numberOfFilesToScan);

            if (isQueryFinshed(queryId)) {
                sendNewQueryPermit(queryId);
                removeQueryIdFromMappings(queryId);
            }

        } else if (tuple.getSourceStreamId().equals(Streams.BPlusTreeQueryStream) ||
                tuple.getSourceStreamId().equals(Streams.FileSystemQueryStream)) {
            long queryId = tuple.getLong(0);



            if (tuple.getSourceStreamId().equals(Streams.FileSystemQueryStream)) {
                FileScanMetrics metrics = (FileScanMetrics) tuple.getValue(2);
                putFileScanMetrics(queryId, metrics);
            }

            Integer counter = queryIdToCounter.get(queryId);
            if (counter == null) {
                counter = 1;
            } else {
                counter += 1;
            }

            ArrayList<byte[]> serializedTuples = (ArrayList) tuple.getValue(1);


            /*
            for (int i = 0; i < serializedTuples.size(); ++i) {
                Values deserializedTuple = null;
                try {
//                    deserializedTuple = schema.deserialize(serializedTuples.get(i));
                    deserializedTuple = DeserializationHelper.deserialize(serializedTuples.get(i));
                } catch (IOException e) {
                    e.printStackTrace();
                }
                System.out.println(deserializedTuple);
            }
            */

            Integer numberOfTuples = queryIdToNumberOfTuples.get(queryId);
            if (numberOfTuples == null)
                numberOfTuples = 0;
            numberOfTuples += serializedTuples.size();
            queryIdToCounter.put(queryId, counter);
            queryIdToNumberOfTuples.put(queryId, numberOfTuples);

            if (isQueryFinshed(queryId)) {
                sendNewQueryPermit(queryId);
                removeQueryIdFromMappings(queryId);
            }

            collector.ack(tuple);
        }
    }

    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
//        outputFieldsDeclarer.declareStream(NormalDistributionIndexingTopology.NewQueryStream
//                , new Fields("queryId", "New Query"));

        outputFieldsDeclarer.declareStream(NormalDistributionIndexingTopology.NewQueryStream
                , new Fields("queryId", "New Query", "metrics", "numberOfFilesToScan"));

//        outputFieldsDeclarer.declareStream(NormalDistributionIndexingTopology.TimeCostInformationStream
//                , new Fields("queryId", "timeCostOfReadFile", "timeCostOfDeserializationALeaf",
//                        "timeCostOfDeserializationATree"));
    }


    private boolean isQueryFinshed(Long queryId) {
        if (queryIdToNumberOfFilesToScan.get(queryId) == null) {
            return false;
        } else if (queryIdToNumberOfTasksToSearch.get(queryId) == null) {
            return false;
        } else if (queryIdToCounter.get(queryId) == null) {
            return false;
        } else {
            int counter = queryIdToCounter.get(queryId);
            int numberOfFilesToScan = queryIdToNumberOfFilesToScan.get(queryId);
            int tasksToSearch = queryIdToNumberOfTasksToSearch.get(queryId);
            if (numberOfFilesToScan + tasksToSearch == counter) {
                return true;
            }
        }
        return false;
    }

    private void sendNewQueryPermit(Long queryId) {
        FileScanMetrics metrics = queryIdToFileScanMetrics.get(queryId);
        int numberOfFilesToScan = queryIdToNumberOfFilesToScan.get(queryId);
        collector.emit(Streams.NewQueryStream,
                new Values(queryId, new String("New query can be executed"),
                        metrics, numberOfFilesToScan));
    }

    private void removeQueryIdFromMappings(Long queryId) {
        queryIdToCounter.remove(queryId);
        queryIdToNumberOfFilesToScan.remove(queryId);
        queryIdToNumberOfTasksToSearch.remove(queryId);
        queryIdToFileScanMetrics.remove(queryId);
    }

    private void putFileScanMetrics(Long queryId, FileScanMetrics metrics) {
        FileScanMetrics fileScanMetrics = queryIdToFileScanMetrics.get(queryId);
        if (fileScanMetrics == null) {
            queryIdToFileScanMetrics.put(queryId, metrics);
        } else {
            fileScanMetrics.addWithAnotherMetrics(metrics);
            queryIdToFileScanMetrics.put(queryId, fileScanMetrics);
        }
    }
}