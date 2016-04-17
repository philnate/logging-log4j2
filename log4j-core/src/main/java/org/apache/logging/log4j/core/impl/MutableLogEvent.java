package org.apache.logging.log4j.core.impl;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.util.Constants;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ReusableMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.util.PropertiesUtil;
import org.apache.logging.log4j.util.Strings;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.util.Map;

/**
 * Mutable implementation of the {@code LogEvent} interface.
 */
public class MutableLogEvent implements LogEvent, ReusableMessage {
    private static final int INITIAL_REUSABLE_MESSAGE_SIZE = size("log4j.initialReusableMsgSize", 128);
    private static final int MAX_REUSABLE_MESSAGE_SIZE = size("log4j.maxReusableMsgSize", (128 * 2 + 2) * 2 + 2);
    private static final Object[] PARAMS = new Object[0];
    private static final Message EMPTY = new SimpleMessage(Strings.EMPTY);

    private String loggerFqcn;
    private Marker marker;
    private Level level;
    private String loggerName;
    private Message message;
    private Throwable thrown;
    private long timeMillis;
    private Map<String, String> contextMap;
    private ThreadContext.ContextStack contextStack;
    private long threadId;
    private String threadName;
    private int threadPriority;
    private boolean includeLocation;
    private boolean endOfBatch = false;
    private long nanoTime;
    private ThrowableProxy thrownProxy;
    private StackTraceElement source;
    private StringBuilder messageText;

    private static int size(final String property, final int defaultValue) {
        return PropertiesUtil.getProperties().getIntegerProperty(property, defaultValue);
    }

    /**
     * Initialize the fields of this {@code MutableLogEvent} from another event.
     * Similar in purpose and usage as {@link org.apache.logging.log4j.core.impl.Log4jLogEvent.LogEventProxy},
     * but a mutable version.
     * <p>
     * This method is used on async logger ringbuffer slots holding MutableLogEvent objects in each slot.
     * </p>
     *
     * @param event the event to copy data from
     */
    public void initFrom(final LogEvent event) {
        this.loggerFqcn = event.getLoggerFqcn();
        this.marker = event.getMarker();
        this.level = event.getLevel();
        this.loggerName = event.getLoggerName();
        this.timeMillis = event.getTimeMillis();
        this.thrown = event.getThrown();
        this.thrownProxy = event.getThrownProxy();
        this.contextMap = event.getContextMap();
        this.contextStack = event.getContextStack();
        this.source = event.isIncludeLocation() ? event.getSource() : null;
        this.threadId = event.getThreadId();
        this.threadName = event.getThreadName();
        this.threadPriority = event.getThreadPriority();
        this.endOfBatch = event.isEndOfBatch();
        this.nanoTime = event.getNanoTime();
        setMessage(event.getMessage());
    }

    public void clear() {
        loggerFqcn = null;
        marker = null;
        level = null;
        loggerName = null;
        message = null;
        thrown = null;
        thrownProxy = null;
        source = null;
        contextMap = null;
        contextStack = null;
        threadName = null;
        // primitive fields that cannot be cleared:
        //timeMillis;
        //threadId;
        //threadPriority;
        //includeLocation;
        //endOfBatch;
        //nanoTime;
    }

    @Override
    public String getLoggerFqcn() {
        return loggerFqcn;
    }

    public void setLoggerFqcn(String loggerFqcn) {
        this.loggerFqcn = loggerFqcn;
    }

    @Override
    public Marker getMarker() {
        return marker;
    }

    public void setMarker(Marker marker) {
        this.marker = marker;
    }

    @Override
    public Level getLevel() {
        return level;
    }

    public void setLevel(Level level) {
        this.level = level;
    }

    @Override
    public String getLoggerName() {
        return loggerName;
    }

    public void setLoggerName(String loggerName) {
        this.loggerName = loggerName;
    }

    @Override
    public Message getMessage() {
        if (message == null) {
            return (messageText == null) ? EMPTY : this;
        }
        return message;
    }

    public void setMessage(final Message msg) {
        if (msg instanceof ReusableMessage) {
            ((ReusableMessage) msg).formatTo(getMessageTextForWriting());
        } else {
            // if the Message instance is reused, there is no point in freezing its message here
            if (!Constants.FORMAT_MESSAGES_IN_BACKGROUND && msg != null) { // LOG4J2-898: user may choose
                msg.getFormattedMessage(); // LOG4J2-763: ask message to freeze parameters
            }
            this.message = msg;
        }
    }

    private StringBuilder getMessageTextForWriting() {
        if (messageText == null) {
            // Should never happen:
            // only happens if user logs a custom reused message when Constants.ENABLE_THREADLOCALS is false
            messageText = new StringBuilder(INITIAL_REUSABLE_MESSAGE_SIZE);
        }
        messageText.setLength(0);
        return messageText;
    }

    /**
     * @see ReusableMessage#getFormattedMessage()
     */
    @Override
    public String getFormattedMessage() {
        return messageText.toString();
    }

    /**
     * @see ReusableMessage#getFormat()
     */
    @Override
    public String getFormat() {
        return null;
    }

    /**
     * @see ReusableMessage#getParameters()
     */
    @Override
    public Object[] getParameters() {
        return PARAMS;
    }

    /**
     * @see ReusableMessage#getThrowable()
     */
    @Override
    public Throwable getThrowable() {
        return getThrown();
    }

    /**
     * @see ReusableMessage#formatTo(StringBuilder)
     */
    @Override
    public void formatTo(final StringBuilder buffer) {
        buffer.append(messageText);
    }

    @Override
    public Throwable getThrown() {
        return thrown;
    }

    public void setThrown(Throwable thrown) {
        this.thrown = thrown;
    }

    @Override
    public long getTimeMillis() {
        return timeMillis;
    }

    public void setTimeMillis(long timeMillis) {
        this.timeMillis = timeMillis;
    }

    /**
     * Returns the ThrowableProxy associated with the event, or null.
     * @return The ThrowableProxy associated with the event.
     */
    @Override
    public ThrowableProxy getThrownProxy() {
        if (thrownProxy == null && thrown != null) {
            thrownProxy = new ThrowableProxy(thrown);
        }
        return thrownProxy;
    }

    /**
     * Returns the StackTraceElement for the caller. This will be the entry that occurs right
     * before the first occurrence of FQCN as a class name.
     * @return the StackTraceElement for the caller.
     */
    @Override
    public StackTraceElement getSource() {
        if (source != null) {
            return source;
        }
        if (loggerFqcn == null || !includeLocation) {
            return null;
        }
        source = Log4jLogEvent.calcLocation(loggerFqcn);
        return source;
    }

    @Override
    public Map<String, String> getContextMap() {
        return contextMap;
    }

    public void setContextMap(Map<String, String> contextMap) {
        this.contextMap = contextMap;
    }

    @Override
    public ThreadContext.ContextStack getContextStack() {
        return contextStack;
    }

    public void setContextStack(ThreadContext.ContextStack contextStack) {
        this.contextStack = contextStack;
    }

    @Override
    public long getThreadId() {
        return threadId;
    }

    public void setThreadId(long threadId) {
        this.threadId = threadId;
    }

    @Override
    public String getThreadName() {
        return threadName;
    }

    public void setThreadName(String threadName) {
        this.threadName = threadName;
    }

    @Override
    public int getThreadPriority() {
        return threadPriority;
    }

    public void setThreadPriority(int threadPriority) {
        this.threadPriority = threadPriority;
    }

    @Override
    public boolean isIncludeLocation() {
        return includeLocation;
    }

    @Override
    public void setIncludeLocation(boolean includeLocation) {
        this.includeLocation = includeLocation;
    }

    @Override
    public boolean isEndOfBatch() {
        return endOfBatch;
    }

    @Override
    public void setEndOfBatch(boolean endOfBatch) {
        this.endOfBatch = endOfBatch;
    }

    @Override
    public long getNanoTime() {
        return nanoTime;
    }

    public void setNanoTime(long nanoTime) {
        this.nanoTime = nanoTime;
    }

    /**
     * Creates a LogEventProxy that can be serialized.
     * @return a LogEventProxy.
     */
    protected Object writeReplace() {
        return new Log4jLogEvent.LogEventProxy(this, this.includeLocation);
    }

    private void readObject(final ObjectInputStream stream) throws InvalidObjectException {
        throw new InvalidObjectException("Proxy required");
    }
}