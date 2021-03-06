package io.pravega.example.idracdemo.flinkprocessor.jobs;

import io.pravega.client.admin.StreamManager;
import io.pravega.client.stream.StreamCut;
import io.pravega.connectors.flink.FlinkPravegaReader;
import io.pravega.connectors.flink.serialization.JsonDeserializationSchema;
import io.pravega.example.idracdemo.flinkprocessor.AbstractJob;
import io.pravega.example.idracdemo.flinkprocessor.AppConfiguration;
import io.pravega.example.idracdemo.flinkprocessor.datatypes.FlatMetricReport;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.influxdb.InfluxDBPoint;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.TimeZone;

/**
 * Read the stream as MetricReport objects and insert into influxDB
 */
public class PravegaMetricReportToInfluxDbJob extends AbstractJob {
    private static final TimeCharacteristic characteristic = TimeCharacteristic.ProcessingTime;
    private static Logger log = LoggerFactory.getLogger(PravegaMetricReportToInfluxDbJob.class);

    public PravegaMetricReportToInfluxDbJob(AppConfiguration appConfiguration) {
        super(appConfiguration);
    }

    public void run() {
        try {
            StreamExecutionEnvironment env = initializeFlinkStreaming();
            createStream(appConfiguration.getInputStreamConfig());

            StreamManager streamManager = StreamManager.create(appConfiguration.getPravegaConfig().getClientConfig());
            StreamCut tailStreamCut = streamManager.getStreamInfo(appConfiguration.getInputStreamConfig().getStream().getScope(),
                    appConfiguration.getInputStreamConfig().getStream().getStreamName()).getTailStreamCut();

            FlinkPravegaReader<FlatMetricReport> flinkPravegaReader = FlinkPravegaReader.builder()
                    .withPravegaConfig(appConfiguration.getPravegaConfig())
                    .forStream(appConfiguration.getInputStreamConfig().getStream(), tailStreamCut)
                    .withDeserializationSchema(new JsonDeserializationSchema(FlatMetricReport.class))
                    .build();

            DataStream<FlatMetricReport> events = env
                    .addSource(flinkPravegaReader)
                    .name("read-flatten-events");

            DataStream<InfluxDBPoint> dbEvents = events
                    .keyBy(new KeySelector<FlatMetricReport, Tuple2<String, String>>() {
                        @Override
                        public Tuple2<String, String> getKey(FlatMetricReport report) throws Exception {
                            log.debug("###### KeyBy ###### ID " + report.Id + " IPAddr " + report.RemoteAddr);
                            return new Tuple2<>(report.Id, report.RemoteAddr);
                        }
                    })
                    .flatMap(new MetricsProcess())
                    .name("metric-events-to-influxdb-point")
                    .uid("metric-events-to-influxdb-point");

            addMetricsSink(dbEvents, appConfiguration.getWriterParallelism(), "metrics");

            env.execute(PravegaMetricReportToInfluxDbJob.class.getSimpleName());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private class MetricsProcess implements FlatMapFunction<FlatMetricReport, InfluxDBPoint> {
        @Override
        public void flatMap(FlatMetricReport in, Collector<InfluxDBPoint> out) {
            if (in != null) {
                // Create InfluxDb entry
                String measurement = "flat_metric_events";

                DateFormat desiredFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
                desiredFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date date;
                try {
                    date = desiredFormat.parse(in.Timestamp);
                } catch (ParseException e) {
                    throw new RuntimeException(e);
                }

                // convert time to mills;
                long timestamp = date.getTime();

                HashMap<String, String> tags = new HashMap<>();
                tags.put("Id", in.Id);
                tags.put("MetricId", in.MetricId);

                if(in.RackLabel != null)
                    tags.put("RackLabel", in.RackLabel);

                if(in.ContextID != null)
                    tags.put("ContextID", in.ContextID);

                tags.put("RemoteAddr", in.RemoteAddr);

                HashMap<String, Object> fields = new HashMap<>();
                fields.put("MetricValue", in.MetricValue);
                out.collect(new InfluxDBPoint(measurement + "_" + in.Id, timestamp, tags, fields));

            }

        }
    }

}
