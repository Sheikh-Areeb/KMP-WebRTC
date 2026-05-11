/**
 * WebRTC Signaling Server
 *
 * What does a signaling server do?
 * ─────────────────────────────────
 * WebRTC peers need to exchange two kinds of metadata BEFORE they can talk
 * directly to each other:
 *
 *  1. SDP  (Session Description Protocol) — describes codecs, IP addresses,
 *          and media capabilities.  One peer creates an "offer", the other
 *          creates an "answer".
 *
 *  2. ICE Candidates — possible network paths (IP:port pairs) that each peer
 *          can be reached on.  They trickle in over time as the ICE agent
 *          discovers them.
 *
 * The signaling server is just a relay: it has NO knowledge of the call
 * contents.  Once ICE negotiation finishes the two peers talk peer-to-peer
 * and this server is no longer involved in the media stream.
 *
 * Protocol (JSON over WebSocket):
 * ─────────────────────────────────
 *  Client → Server
 *    { type: "join",          roomId, userId }
 *    { type: "offer",         sdp, to, from }
 *    { type: "answer",        sdp, to, from }
 *    { type: "ice_candidate", candidate, sdpMid, sdpMLineIndex, to, from }
 *
 *  Server → Client
 *    { type: "room_joined",  roomId, yourId, peerId }   ← peerId is null if first in room
 *    { type: "peer_joined",  peerId }
 *    { type: "peer_left",    peerId }
 *    { type: "error",        message }
 *    (offer / answer / ice_candidate are forwarded as-is to the target peer)
 */

const { WebSocketServer } = require("ws");

const PORT = process.env.PORT || 3000;
const wss = new WebSocketServer({ port: PORT });

// rooms: Map<roomId, Map<userId, WebSocket>>
const rooms = new Map();

// clientInfo: Map<WebSocket, { roomId, userId }> — for cleanup on disconnect
const clientInfo = new Map();

wss.on("connection", (ws) => {
  console.log("[+] Client connected");

  ws.on("message", (data) => {
    let msg;
    try {
      msg = JSON.parse(data.toString());
    } catch {
      send(ws, { type: "error", message: "Invalid JSON" });
      return;
    }

    switch (msg.type) {
      case "join":
        handleJoin(ws, msg);
        break;
      case "offer":
      case "answer":
      case "ice_candidate":
        forwardToPeer(ws, msg);
        break;
      default:
        send(ws, { type: "error", message: `Unknown message type: ${msg.type}` });
    }
  });

  ws.on("close", () => handleDisconnect(ws));
  ws.on("error", (err) => console.error("WebSocket error:", err));
});

// ── Handlers ──────────────────────────────────────────────────────────────

function handleJoin(ws, { roomId, userId }) {
  if (!roomId || !userId) {
    send(ws, { type: "error", message: "join requires roomId and userId" });
    return;
  }

  // Create room if this is the first visitor
  if (!rooms.has(roomId)) {
    rooms.set(roomId, new Map());
  }

  const room = rooms.get(roomId);

  if (room.size >= 2) {
    send(ws, { type: "error", message: "Room is full (max 2 peers)" });
    return;
  }

  // Find the existing peer (if any) before adding ourselves
  const existingPeerId = room.size === 1 ? [...room.keys()][0] : null;

  room.set(userId, ws);
  clientInfo.set(ws, { roomId, userId });

  console.log(`[+] ${userId} joined room "${roomId}" (${room.size}/2)`);

  // Tell the joiner whether anyone is already waiting
  send(ws, {
    type: "room_joined",
    roomId,
    yourId: userId,
    peerId: existingPeerId,    // null if we are first
  });

  // Tell the existing peer that someone new arrived
  if (existingPeerId) {
    const peerWs = room.get(existingPeerId);
    send(peerWs, { type: "peer_joined", peerId: userId });
  }
}

function forwardToPeer(ws, msg) {
  const info = clientInfo.get(ws);
  if (!info) {
    send(ws, { type: "error", message: "Not in a room — send join first" });
    return;
  }

  const { roomId } = info;
  const room = rooms.get(roomId);
  const targetWs = room?.get(msg.to);

  if (!targetWs) {
    send(ws, { type: "error", message: `Peer ${msg.to} not found in room` });
    return;
  }

  // Forward message verbatim — the server never reads SDP or ICE content
  send(targetWs, msg);
}

function handleDisconnect(ws) {
  const info = clientInfo.get(ws);
  if (!info) return;

  const { roomId, userId } = info;
  clientInfo.delete(ws);

  const room = rooms.get(roomId);
  if (!room) return;

  room.delete(userId);
  console.log(`[-] ${userId} left room "${roomId}"`);

  // Notify the remaining peer
  for (const [, peerWs] of room) {
    send(peerWs, { type: "peer_left", peerId: userId });
  }

  // Clean up empty rooms
  if (room.size === 0) {
    rooms.delete(roomId);
    console.log(`[~] Room "${roomId}" deleted (empty)`);
  }
}

// ── Utility ───────────────────────────────────────────────────────────────

function send(ws, obj) {
  if (ws.readyState === ws.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

console.log(`Signaling server running on ws://localhost:${PORT}`);
