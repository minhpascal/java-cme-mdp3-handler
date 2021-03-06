/*
 * Copyright 2004-2016 EPAM Systems
 * This file is part of Java Market Data Handler for CME Market Data (MDP 3.0).
 * Java Market Data Handler for CME Market Data (MDP 3.0) is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 * Java Market Data Handler for CME Market Data (MDP 3.0) is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with Java Market Data Handler for CME Market Data (MDP 3.0).
 * If not, see <http://www.gnu.org/licenses/>.
 */

package com.epam.cme.mdp3.core.control;

import com.epam.cme.mdp3.*;
import com.epam.cme.mdp3.core.channel.ChannelContext;
import com.epam.cme.mdp3.core.channel.MdpFeedContext;
import com.epam.cme.mdp3.mktdata.MdConstants;
import com.epam.cme.mdp3.mktdata.RequestForQuoteHandler;
import com.epam.cme.mdp3.mktdata.SecurityStatusHandler;
import com.epam.cme.mdp3.mktdata.enums.MDEntryType;
import com.epam.cme.mdp3.sbe.message.SbeConstants;
import com.epam.cme.mdp3.sbe.message.meta.MdpMessageType;
import com.epam.cme.mdp3.sbe.message.meta.SbePrimitiveType;
import com.epam.cme.mdp3.sbe.schema.MdpMessageTypes;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.epam.cme.mdp3.mktdata.MdConstants.INCR_RFRSH_MD_ENTRY_TYPE;

public class ChannelController {
    private static final Logger logger = LoggerFactory.getLogger(ChannelController.class);
    private ChannelContext channelContext;
    private static final int PRCD_SNPT_COUNT_NULL = (int) SbePrimitiveType.Int32.getNullValue();
    private static final int SNAPSHOT_CYCLES_MAX = 3;

    private final MdpMessageTypes mdpMessageTypes;
    private final PacketQueue incrementQueue;
    private final RequestForQuoteHandler requestForQuoteHandler;
    private final SecurityStatusHandler securityStatusHandler;
    private int snptMsgCountDown = PRCD_SNPT_COUNT_NULL;

    private ChannelState state = ChannelState.INITIAL;
    private long prcdSeqNum = 0;
    private long lastMsgSeqNumPrcd369 = 0;
    private Lock lock = new ReentrantLock();

    private long lastIncrPcktReceived = 0;
    private boolean wasChannelResetInPrcdPacket = false;

    private final EventController eventController = new InMemoryEventController();

    private final EventCommitFunction eventCommitFunction = (securityId) -> {
        if (channelContext != null) {
            final InstrumentController instController = channelContext.findInstrumentController(securityId, null);

            if (instController != null) {
                instController.commitEvent();
            }
        }
    };

    public ChannelController(final ChannelContext channelContext, final int queueSize, final int queueSlotBufSize) {
        this.mdpMessageTypes = channelContext.getMdpMessageTypes();
        this.channelContext = channelContext;
        this.requestForQuoteHandler = new RequestForQuoteHandler(channelContext);
        this.securityStatusHandler = new SecurityStatusHandler(channelContext);
        this.incrementQueue = new PacketQueue(queueSize, queueSlotBufSize);
    }

    public PacketQueue getIncrementQueue() {
        return incrementQueue;
    }

    public long getPrcdSeqNum() {
        return prcdSeqNum;
    }

    public ChannelState getState() {
        return state;
    }

    public void lock() {
        this.lock.lock();
    }

    public void unlock() {
        this.lock.unlock();
    }

    public void switchState(final ChannelState newState) {
        switchState(this.state, newState);
    }

    public void switchState(final ChannelState prevState, final ChannelState newState) {
        this.state = newState;
        channelContext.notifyChannelStateListeners(prevState, newState);
    }

    private void processQueue(final MdpFeedContext feedContext) {
        this.prcdSeqNum = this.lastMsgSeqNumPrcd369;
        final MdpPacket mdpPacket = feedContext.getMdpPacket();
        int queuePktDataLen;

        do {
            queuePktDataLen = this.incrementQueue.poll(prcdSeqNum + 1, mdpPacket);
            if (queuePktDataLen > 0) {
                handleIncrementalPacket(feedContext, mdpPacket, true);
                this.prcdSeqNum++;
            }
        } while (queuePktDataLen > 0);
    }

    public void handleIncrementalPacket(final MdpFeedContext feedContext, final MdpPacket mdpPacket) {
        handleIncrementalPacket(feedContext, mdpPacket, false);
    }

    public void handleIncrementalPacket(final MdpFeedContext feedContext, final MdpPacket mdpPacket, final boolean fromQueue) {
        final long msgSeqNum = mdpPacket.getMsgSeqNum();
        logger.trace("Feed {}{} | handleIncrementalPacket: this.prcdSeqNum={}, mdpPacket.getMsgSeqNum()={}",
                    feedContext.getFeedType(), feedContext.getFeed(), this.prcdSeqNum, msgSeqNum);
        lock.lock();
        try {
            this.lastIncrPcktReceived = System.currentTimeMillis();

            if (fromQueue || this.incrementQueue.push(msgSeqNum, mdpPacket)) {
                handleIncrementalMessages(feedContext, msgSeqNum, mdpPacket);
            }
        } finally {
            lock.unlock();
        }
    }

    private void updateSecurityFromIncrementalRefresh(final MdpFeedContext feedContext, final long msgSeqNum,
                                                      final short matchEventIndicator, final MdpGroup incrGroup,
                                                      final InstrumentController instController, final int secId) {
        if (instController != null) {
            if (this.channelContext.hasMdListeners()) {
                this.eventController.logSecurity(secId);
            }
            instController.onIncrementalRefresh(feedContext, msgSeqNum, matchEventIndicator, incrGroup);
        }
    }

    private void handleMarketDataIncrementalRefresh(final MdpFeedContext feedContext, final MdpMessage mdpMessage, final long msgSeqNum, final short matchEventIndicator) {
        final MdpGroup incrGroup = feedContext.getMdpGroupObj();
        InstrumentController instController = null;
        mdpMessage.getGroup(MdConstants.INCR_RFRSH_GRP_TAG, incrGroup);
        while (incrGroup.hashNext()) {
            incrGroup.next();
            final MDEntryType mdEntryType = MDEntryType.fromFIX(incrGroup.getChar(INCR_RFRSH_MD_ENTRY_TYPE));
            if (mdEntryType == MDEntryType.EmptyBook) {
                handleChannelReset(mdpMessage);
            } else {
                final int secId = incrGroup.getInt32(MdConstants.SECURITY_ID);
                if (instController == null || instController.getSecurityId() != secId) {
                    instController = channelContext.findInstrumentController(secId, null);
                }
                updateSecurityFromIncrementalRefresh(feedContext, msgSeqNum, matchEventIndicator, incrGroup, instController, secId);
            }
        }
    }

    private void handleChannelReset(final MdpMessage resetMessage) {
        this.channelContext.notifyChannelResetListeners(resetMessage);
        this.prcdSeqNum = 0;
        this.lastMsgSeqNumPrcd369 = 0;
        this.wasChannelResetInPrcdPacket = true;
        channelContext.getInstruments().resetAll();
        this.incrementQueue.clear();
        switchState(ChannelState.SYNC);
        if (this.channelContext.hasMdListeners()) this.eventController.reset();
        this.channelContext.notifyChannelResetFinishedListeners(resetMessage);
    }

    private void handleIncrementalMessages(final MdpFeedContext feedContext, final long msgSeqNum, final MdpPacket mdpPacket) {
        final Iterator<MdpMessage> mdpMessageIterator = mdpPacket.iterator();
        while (mdpMessageIterator.hasNext()) {
            final MdpMessage mdpMessage = mdpMessageIterator.next();

            final MdpMessageType messageType = mdpMessageTypes.getMessageType(mdpMessage.getSchemaId());
            mdpMessage.setMessageType(messageType);
            final short matchEventIndicator = mdpMessage.getUInt8(SbeConstants.MATCHEVENTINDICATOR_TAG);

            if (messageType.getSemanticMsgType() == SemanticMsgType.MarketDataIncrementalRefresh) {
                handleMarketDataIncrementalRefresh(feedContext, mdpMessage, msgSeqNum, matchEventIndicator);
            } else if (messageType.getSemanticMsgType() == SemanticMsgType.QuoteRequest) {
                this.requestForQuoteHandler.handle(feedContext, mdpMessage);
            } else if (messageType.getSemanticMsgType() == SemanticMsgType.SecurityStatus) {
                this.securityStatusHandler.handle(mdpMessage, matchEventIndicator);
            } else if (messageType.getSemanticMsgType() == SemanticMsgType.SecurityDefinition) {
                this.channelContext.getInstruments().onMessage(feedContext, mdpMessage);
            }
            if (channelContext.hasMdListeners() && MatchEventIndicator.hasEndOfEvent(matchEventIndicator)) {
                this.eventController.commit(this.eventCommitFunction);
            }
        }
        if (this.wasChannelResetInPrcdPacket) {
            this.wasChannelResetInPrcdPacket = false;
        } else {
            this.prcdSeqNum = msgSeqNum;
        }
    }

    private void stopSnapshotListening(final MdpFeedContext feedContext) {
        channelContext.stopSnapshotFeeds();
        if (this.state != ChannelState.SYNC) {
            this.prcdSeqNum = this.lastMsgSeqNumPrcd369;
            if (this.channelContext.hasMdListeners()) this.eventController.reset();
            if (this.state == ChannelState.INITIAL) switchState(this.state, ChannelState.SYNC);
            processQueue(feedContext);
        }
    }

    private void handleSnapshotMessage(final MdpFeedContext feedContext, final long snptPktSeqNum, final MdpMessage mdpMessage) {
        final long lastMsgSeqNumProcessed = mdpMessage.getUInt32(369);
        logger.trace("Feed {}{} | handleSnapshotMessage: this.prcdSeqNum={}, this.lastMsgSeqNumPrcd369={}, mdpMessage.getUInt32(369)={}",
                    feedContext.getFeedType(), feedContext.getFeed(), this.prcdSeqNum, this.lastMsgSeqNumPrcd369, lastMsgSeqNumProcessed);
        if (lastMsgSeqNumProcessed > this.prcdSeqNum) {
            if (snptPktSeqNum == 1 && canStopSnapshotListening(this.snptMsgCountDown)) {
                stopSnapshotListening(feedContext);
                return;
            }
            handleSnapshotMessage(feedContext, mdpMessage);
            if (this.snptMsgCountDown == PRCD_SNPT_COUNT_NULL) {
                final int totalNumReports = (int) mdpMessage.getUInt32(911) * SNAPSHOT_CYCLES_MAX;
                this.snptMsgCountDown = totalNumReports;
            }
            this.snptMsgCountDown--;
            this.lastMsgSeqNumPrcd369 = lastMsgSeqNumProcessed;
        }
    }

    public void handleSnapshotPacket(final MdpFeedContext feedContext, final MdpPacket mdpPacket) {
        lock.lock();
        try {
            final Iterator<MdpMessage> mdpMessageIterator = mdpPacket.iterator();
            while (mdpMessageIterator.hasNext()) {
                final MdpMessage mdpMessage = mdpMessageIterator.next();
                final MdpMessageType messageType = mdpMessageTypes.getMessageType(mdpMessage.getSchemaId());
                final SemanticMsgType msgType = messageType.getSemanticMsgType();
                if (msgType == SemanticMsgType.MarketDataSnapshotFullRefresh) {
                    mdpMessage.setMessageType(messageType);
                    handleSnapshotMessage(feedContext, mdpPacket.getMsgSeqNum(), mdpMessage);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void handleSnapshotMessage(final MdpFeedContext feedContext, final MdpMessage mdpMessage) {
        final int secId = mdpMessage.getInt32(48);
        final InstrumentController instController = channelContext.findInstrumentController(secId, null);

        if (instController != null) {
            instController.onSnapshotFullRefresh(feedContext, mdpMessage);
        }
    }

    private boolean canStopSnapshotListening(final int msgLeft) {
        return msgLeft <= 0 && this.getIncrementQueue().exist(this.lastMsgSeqNumPrcd369 + 1);
    }

    public void resetSnapshotCycleCount() {
        this.snptMsgCountDown = PRCD_SNPT_COUNT_NULL;
    }

    public long getLastIncrPcktReceived() {
        return lastIncrPcktReceived;
    }

    public void close() {
        this.incrementQueue.release();
    }
}
