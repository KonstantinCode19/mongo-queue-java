package ru.infon.queuebox.mongo;

import gaillard.mongo.MongoQueueCore;
import org.bson.Document;
import ru.infon.queuebox.*;
import ru.infon.queuebox.mongo.MongoConnection;
import ru.infon.queuebox.mongo.MongoJacksonSerializer;

import java.util.*;

/**
 * 06.06.2017
 * @author KostaPC
 * 2017 Infon ZED
 **/
public class RoutedQueueBehave<T extends RoutedMessage> implements QueueBehave<T> {

    private static final String FIELD_SOURCE = "source";
    private static final String FIELD_DESCTINATION = "destination";
    private static final String FIELD_ID = "id";

    public static final String PROPERTY_FETCH_LIMIT = "queue.fetch.limit";

    private static final int DEFAULT_FETCH_LIMIT = 100;

    private final QueueSerializer<T> serializer;
    private final MongoConnection connection;
    private final MongoQueueCore mongoQueueCore;

    private int fetchLimit = DEFAULT_FETCH_LIMIT;

    public RoutedQueueBehave(Properties properties, Class<T> packetClass) {
        this.serializer = new MongoJacksonSerializer<>(packetClass);
        this.connection = new MongoConnection(properties);
        this.mongoQueueCore = new MongoQueueCore(
                connection.getMongoCollection(Document.class)
        );
        Document indexDocument = new Document();
        indexDocument.append(FIELD_DESCTINATION,1);
        mongoQueueCore.ensureGetIndex(indexDocument);
        try {
            this.fetchLimit = Integer.parseInt(properties.getProperty(PROPERTY_FETCH_LIMIT));
        } catch (NumberFormatException ignore) {}
    }

    @Override
    public int getThreadsCount() {
        return this.connection.getThreadsCount();
    }

    @Override
    public void put(MessageContainer<T> event) {
        T message = event.getMessage();
        Document queueMessage = serializer.serialize(message);
        queueMessage.append(FIELD_SOURCE, message.getSource());
        queueMessage.append(FIELD_DESCTINATION, message.getDestination());
        this.mongoQueueCore.send(
                serializer.serialize(event.getMessage()),
                new Date(),
                event.getPriority()
        );
    }

    @Override
    public Collection<MessageContainer<T>> find(QueueConsumer<T> consumer) {
        Document query = new Document();
        query.append(FIELD_DESCTINATION, consumer.getConsumerId());
        List<MessageContainer<T>> resultList = new LinkedList<>();
        int limit = fetchLimit;
        while (limit-->0) {
            Document queueMessage = mongoQueueCore.get(query, 10, 100, 0);
            if(queueMessage==null) {
                break;
            }
            Object id = queueMessage.get(FIELD_ID);
            queueMessage.remove(FIELD_ID);
            String destination = queueMessage.getString(FIELD_DESCTINATION);
            String source = queueMessage.getString(FIELD_SOURCE);
            queueMessage.remove(FIELD_DESCTINATION);
            queueMessage.remove(FIELD_SOURCE);
            T message = serializer.deserialize(queueMessage);
            message.setSource(source);
            message.setDestination(destination);
            MessageContainer<T> messageContainer = new MessageContainer<>(message);
            messageContainer.setId(id);
            resultList.add(messageContainer);
        }
        return resultList;
    }

    @Override
    public void remove(MessageContainer<T> packet) {
        Document query = new Document();
        query.append(FIELD_ID, packet.getId());
        mongoQueueCore.ack(query);
    }

    @Override
    public void reset(MessageContainer<T> event) {
        T message = event.getMessage();
        Document queueMessage = serializer.serialize(message);
        queueMessage.append(FIELD_SOURCE, message.getSource());
        queueMessage.append(FIELD_DESCTINATION, message.getDestination());
        queueMessage.append(FIELD_ID, event.getId());
        mongoQueueCore.requeue(queueMessage);
    }
}
