package io.pravega.example.iot.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import org.glassfish.grizzly.http.server.Request;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;

@Path("data/{remoteAddr}")
public class DataHandler {
    private static final Logger Log = LoggerFactory.getLogger(DataHandler.class);

    private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    static {
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    @POST
    @Consumes({"application/json"})
    @Produces({"application/json"})
    public String postData(@Context Request request, String data, @PathParam("remoteAddr") String remoteAddr) throws Exception {
        String timestamp = dateFormat.format(new Date());
        try {
            // Deserialize the JSON message.
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode tree = objectMapper.readTree(data);

            ArrayNode arrayNode;
            if (tree instanceof ArrayNode) {
                arrayNode = (ArrayNode) tree;
            } else if (tree instanceof ObjectNode) {
                arrayNode = objectMapper.createArrayNode();
                arrayNode.add(tree);
            } else {
                throw new java.lang.IllegalArgumentException("Parameter must be a JSON array or object");
            }

            for (JsonNode jsonNode : arrayNode) {
                ObjectNode message = (ObjectNode) jsonNode;
                // Add the remote IP address to JSON message.
                message.put("RemoteAddr", remoteAddr);

                // Add timestamp to JSON message.
                message.put("Timestamp", timestamp);

                // Get or calculate the routing key.
                String routingKeyAttributeName = Parameters.getRoutingKeyAttributeName();
                String routingKey;
                if (routingKeyAttributeName.isEmpty()) {
                    // TODO: Would it be better to use an empty routing key?
                    routingKey = Double.toString(Math.random());
                } else {
                    JsonNode routingKeyNode = message.get(routingKeyAttributeName);
                    routingKey = objectMapper.writeValueAsString(routingKeyNode);
                }

                // Write the message to Pravega.
                Log.debug("routingKey={}, message={}", routingKey, message);
                final CompletableFuture writeFuture = Main.getWriter().writeEvent(routingKey, message);

                // Wait for acknowledgement that the event was durably persisted.
                // TODO: Wait should be an option that can be set by each request.
            //        writeFuture.get();
            }
            return "{}";
        }
        catch (Exception e) {
            Log.error(e.toString());
            throw e;
        }
    }
}
