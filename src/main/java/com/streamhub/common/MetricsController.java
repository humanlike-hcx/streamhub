package com.streamhub.common;

import com.streamhub.common.id.WorkerIdLease;
import com.streamhub.ingest.IngestService;
import com.streamhub.room.RoomRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /metrics：计数器 + 几个当前状态（连接数、房间数、在途数、workerId）。 */
@RestController
public class MetricsController {
    private final Metrics metrics;
    private final RoomRegistry rooms;
    private final IngestService ingest;
    private final WorkerIdLease lease;
    private final StreamhubProperties props;

    public MetricsController(Metrics metrics, RoomRegistry rooms, IngestService ingest, WorkerIdLease lease,
                             StreamhubProperties props) {
        this.metrics = metrics;
        this.rooms = rooms;
        this.ingest = ingest;
        this.lease = lease;
        this.props = props;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        var m = new LinkedHashMap<String, Object>(metrics.snapshot());
        m.put("connections", rooms.connectionCount());
        m.put("rooms", rooms.roomCount());
        m.put("inFlight", ingest.inFlight());
        m.put("workerId", lease.getAsInt());
        m.put("nodeId", props.nodeId());
        return m;
    }
}
