# HLD — Distributed File Transfer System (like BitTorrent)

> **Interview Difficulty:** SDE 3 | **Time Budget:** ~45 min
> **Category:** P2P Systems / Distributed Hash Tables / Graph Algorithms
> **Real-world Analogues:** BitTorrent, WebTorrent, IPFS, Dat Protocol
> **The Core Problem:** Transfer a 10 GB file to 10 million users — without a central server bearing that bandwidth cost — by making every downloader simultaneously an uploader, creating a self-scaling distribution network.

---

## How to Navigate This in 45 Minutes

```
[0:00 -  3:00]  Step 1: Clarify Requirements
[3:00 -  6:00]  Step 2: Capacity Estimation
[6:00 -  9:00]  Step 3: API / Protocol Design
[9:00 - 20:00]  Step 4: High-Level Architecture
[20:00 - 38:00] Step 5: Deep Dives (pick 3 from 5)
[38:00 - 42:00] Step 6: Scale & Resilience
[42:00 - 45:00] Step 7: Trade-offs
```

---

## Table of Contents
1. [Requirements Clarification](#1-requirements-clarification)
2. [Capacity Estimation](#2-capacity-estimation)
3. [Protocol Design](#3-protocol-design)
4. [High-Level Architecture](#4-high-level-architecture)
5. [Deep Dives](#5-deep-dives)
   - 5.1 [File Chunking & Integrity Verification (Merkle Trees)](#51-file-chunking--integrity-verification-merkle-trees)
   - 5.2 [Peer Discovery — Tracker vs DHT (Kademlia)](#52-peer-discovery--tracker-vs-dht-kademlia)
   - 5.3 [Piece Selection — Rarest First Algorithm](#53-piece-selection--rarest-first-algorithm)
   - 5.4 [Choking/Unchoking — Tit-for-Tat Fair Exchange](#54-chokingunchoking--tit-for-tat-fair-exchange)
   - 5.5 [NAT Traversal & Connectivity](#55-nat-traversal--connectivity)
6. [Scale & Resilience](#6-scale--resilience)
7. [Trade-offs & Alternatives](#7-trade-offs--alternatives)
8. [SDE 3 Signals Checklist](#8-sde-3-signals-checklist)

---

## 1. Requirements Clarification

### BitTorrent Terminology (Know Before Interview)

```
TORRENT FILE:   Metadata file (.torrent) containing file info + tracker URL
MAGNET LINK:    URI with infohash; no need for .torrent file (DHT-based)
INFOHASH:       SHA-1/SHA-256 hash of the torrent's info dictionary; unique ID
SEEDER:         Peer with 100% of the file; uploads only
LEECHER:        Peer still downloading; uploads pieces it has
PIECE:          Fixed-size chunk of the file (typically 256 KB-2 MB)
BLOCK:          Sub-division of piece (16 KB); smallest request unit
HAVE message:   Peer announces "I have piece #N"
BITFIELD:       Bitmap showing which pieces a peer has (1=have, 0=need)
CHOKE:          Refuse to upload to a peer (bandwidth management)
UNCHOKE:        Allow uploading to a peer
INTERESTED:     Peer signals it wants pieces from another peer
TRACKER:        Central server that maintains peer lists (optional with DHT)
DHT:            Distributed Hash Table; decentralized peer discovery
SWARM:          All peers (seeders + leechers) for one torrent
SUPER-SEEDER:   Mode that only sends each peer unique pieces (maximizes spread)
```

### Clarifying Questions

```
Q1: "Is this purely P2P (like BitTorrent) or a hybrid with central servers
     for coordination (like Dropbox sync)?"
     → Pure P2P for file distribution; central metadata server optional.

Q2: "What's the primary use case — content distribution (large files to many)
     or user file sync (many small files P2P)?"
     → Content distribution (Linux ISO, game updates). BitTorrent use case.

Q3: "Do we need the tracker, or go fully decentralized (DHT-only)?"
     → Design both; explain trade-offs.

Q4: "Is file integrity / tamper-proofing a requirement?"
     → Yes. Pieces must be verified before accepted (cryptographic hashes).

Q5: "Do we need encryption for privacy (no ISP snooping on what's downloaded)?"
     → Optional: Protocol Encryption (RC4-like stream cipher). Common in BitTorrent.

Q6: "Download only, or also streaming (play video while downloading)?"
     → Add sequential download mode as extension (rarest-first vs sequential trade-off).
```

### Functional Requirements

| # | Requirement | Core Challenge |
|---|---|---|
| FR-1 | User **publishes** a file → system creates torrent metadata | Chunking, hashing, generating .torrent / magnet link |
| FR-2 | User **downloads** file using .torrent or magnet link | Multi-peer download with integrity verification |
| FR-3 | Downloading peer **uploads** pieces it already has | Incentive mechanism (tit-for-tat) |
| FR-4 | **Peer discovery**: find other peers in the swarm | Tracker + DHT fallback |
| FR-5 | **Data integrity**: verify each piece before writing to disk | SHA-256 hash per piece; Merkle tree for efficiency |
| FR-6 | **Resume downloads**: survive crashes, restarts | Checkpoint which pieces are verified |
| FR-7 | File available as long as **at least 1 seeder** exists | No central server dependency |
| FR-8 | **Multiple file** torrents (directories, bundled content) | Multi-file .torrent format |

### Non-Functional Requirements

| Attribute | Target |
|---|---|
| **Download speed** | Saturate client's full upload/download capacity |
| **Scalability** | 10M concurrent peers; swarm self-scales with more peers |
| **Availability** | File available as long as ≥ 1 peer has complete copy |
| **Integrity** | Piece hash mismatch → discard; re-request from another peer |
| **Decentralization** | No single point of failure; tracker is optional |
| **Fairness** | Peers that upload more get better download rates |

---

## 2. Capacity Estimation

```
A realistic large-scale example: Ubuntu 22.04 LTS release torrent

File: Ubuntu ISO = 1.4 GB
Seeders on day 1: 100 official Canonical servers
Peak downloaders (day 1): 5 million

Traditional CDN cost:
  5M users × 1.4 GB = 7 PB of bandwidth on day 1
  CDN cost: ~$0.05/GB × 7PB × 1024 = $358,400 in one day (!!)

BitTorrent benefit:
  Each downloader also uploads what they've received
  Swarm upload capacity: 5M users × avg 1 Mbps upload = 5 Tbps total upload!
  Official servers: 100 × 1 Gbps = 100 Gbps (just 2% of total!)
  Leechers serve 98% of bandwidth to each other → near-zero CDN cost

Piece sizing:
  File: 1.4 GB
  Piece size: 512 KB = 524,288 bytes
  Number of pieces: 1,400 MB / 0.512 MB = 2,734 pieces
  Per-piece hash: SHA-256 = 32 bytes
  Total hash data in .torrent: 2,734 × 32 = 87.5 KB

Block size (smallest request unit):
  Piece: 512 KB; Block: 16 KB (BitTorrent standard)
  Blocks per piece: 512 / 16 = 32 blocks
  Block granularity: needed because TCP is unreliable; blocks can be requested individually

Peer connections per client:
  Typical: 50 simultaneous peers; 4-7 unchoked (actively uploading to)
  Max download: 7 peers × avg 500 KB/s each = 3.5 MB/s (if bandwidth allows)

Tracker load (for tracker-based discovery):
  100K active torrents × avg 500 peers each = 50M tracked (peer, torrent) associations
  Announce rate: each peer announces every 30 min
  Tracker RPS: 50M peers / 1800 seconds = 27,778 announces/sec → manageable
```

---

## 3. Protocol Design

### .torrent File Format (Bencoded Metadata)

```
BitTorrent uses Bencode serialization (not JSON; space-efficient, deterministic):
  Integer: i42e         → 42
  String:  4:spam       → "spam" (length:data)
  List:    l4:spami3ee  → ["spam", 3]
  Dict:    d4:spami3ee  → {"spam": 3}

.torrent file structure:
{
  "announce": "http://tracker.example.com/announce",
  "announce-list": [                     # backup trackers
    ["http://tracker1.example.com/announce"],
    ["udp://tracker2.example.com:6881/announce"]
  ],
  "info": {                              # SHA-256 of this section = infohash
    "name":         "ubuntu-22.04.iso",
    "piece length": 524288,              # 512 KB per piece (bytes)
    "length":       1465silon,           # total file size in bytes
    "pieces":       "<binary SHA-256 hashes concatenated>",  # 32B × N pieces
    # For multi-file torrents:
    "files": [
      { "path": ["dir", "file1.txt"], "length": 1024 },
      { "path": ["dir", "file2.mp4"], "length": 1048576 }
    ]
  },
  "creation date": 1711963200,
  "comment":        "Ubuntu 22.04 LTS Official Release",
  "created by":     "mktorrent 1.1"
}

Infohash = SHA-256(bencode(info_dict))
         = unique 32-byte identifier for this torrent
         = used in DHT lookups and magnet links
```

### Magnet Link Format

```
magnet:?xt=urn:btih:{infohash_hex}
       &dn={display_name_urlencoded}
       &tr={tracker_url_urlencoded}
       &tr={tracker_url2}

Example:
  magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c
         &dn=ubuntu-22.04-desktop-amd64.iso
         &tr=https%3A%2F%2Ftorrent.ubuntu.com%2Fannounce

With magnet link: client has no metadata initially.
  Step 1: Use infohash to find peers via DHT
  Step 2: Download .torrent metadata from peers (Extension Protocol: ut_metadata)
  Step 3: Verify downloaded metadata matches infohash
  Step 4: Begin normal piece download
```

### Peer Wire Protocol (TCP Between Peers)

```
All messages are binary; <length-prefix><message-id><payload>

Handshake (first message after TCP connect):
  [19][BitTorrent protocol][8 reserved bytes (extension flags)][20B infohash][20B peer_id]
  Both peers must send/verify infohash matches → wrong torrent = disconnect

Messages:
  ID  Name        Payload         Purpose
  --  ----        -------         -------
  -   keepalive   (empty)         Maintain connection (sent every 2 min)
  0   choke       (none)          "I will not upload to you"
  1   unchoke     (none)          "I will upload to you now"
  2   interested  (none)          "I want pieces you have"
  3   not-interested (none)       "I don't want any of your pieces"
  4   have        <piece_index>   "I just downloaded piece #N"
  5   bitfield    <bitfield>      Sent after handshake; bitmap of all pieces
  6   request     <index,begin,length>  "Send me block starting at offset 'begin' of piece 'index'"
  7   piece       <index,begin,data>    "Here's block data"
  8   cancel      <index,begin,length>  "Cancel my request for this block"
  13  port        <DHT port>      "My DHT port is X" (for DHT nodes)
  20  extended    <ext_id,data>   Extension protocol messages (ut_metadata, ut_pex)

Extension Protocol (ext_id=20):
  ut_metadata: Transfer .torrent metadata between peers (for magnet links)
  ut_pex:      Peer Exchange — share peer lists without tracker
    {"added": [{ip, port}...], "dropped": [{ip,port}...]}
    → Peers share recently seen peers → decentralized peer discovery
```

---

## 4. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    PUBLISHING A FILE (one-time per torrent)                 │
│                                                                             │
│  Content Creator:                                                           │
│    1. torrent_client.create(file_path, piece_size=512KB)                    │
│    2. Split file → pieces → SHA-256 hash each piece → build Merkle tree    │
│    3. Generate .torrent / magnet link                                       │
│    4. Register infohash with tracker (optional)                             │
│    5. Publish .torrent / magnet link on website / social / forum           │
│    6. Keep client running as SEEDER (serves all pieces to leechers)        │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                    DOWNLOADING A FILE (every downloader)                    │
│                                                                             │
│ ┌──────────────────────────────────────────────────────────────────────┐   │
│ │                    BitTorrent Client                                  │   │
│ │                                                                       │   │
│ │  .torrent / magnet link                                               │   │
│ │         │                                                             │   │
│ │         ▼                                                             │   │
│ │  ┌────────────────────────────────────────────────────────────────┐  │   │
│ │  │  1. PEER DISCOVERY                                             │  │   │
│ │  │     Method A: Tracker HTTP announce                            │  │   │
│ │  │       GET tracker?infohash=...&peer_id=...&port=... →          │  │   │
│ │  │       Response: list of 50 peers {ip, port}                    │  │   │
│ │  │     Method B: DHT (Kademlia) lookup                            │  │   │
│ │  │       DHT.get_peers(infohash) → peer list from DHT network     │  │   │
│ │  │     Method C: PEX (Peer Exchange)                              │  │   │
│ │  │       Ask connected peers for their peer lists                 │  │   │
│ │  └────────────────────────────────────────────────────────────────┘  │   │
│ │         │                                                             │   │
│ │         ▼                                                             │   │
│ │  ┌────────────────────────────────────────────────────────────────┐  │   │
│ │  │  2. CONNECT TO PEERS (up to 50 simultaneous TCP connections)   │  │   │
│ │  │     → Handshake (verify infohash matches)                      │  │   │
│ │  │     → Exchange bitfields (who has what pieces)                 │  │   │
│ │  │     → Send INTERESTED if peer has pieces we need               │  │   │
│ │  └────────────────────────────────────────────────────────────────┘  │   │
│ │         │                                                             │   │
│ │         ▼                                                             │   │
│ │  ┌────────────────────────────────────────────────────────────────┐  │   │
│ │  │  3. PIECE SELECTION (Rarest First Algorithm)                   │  │   │
│ │  │     → Count how many peers have each piece                     │  │   │
│ │  │     → Request rarest pieces first (prevents last-piece delay)  │  │   │
│ │  │     → Pipeline requests: 5 outstanding block requests per peer  │  │   │
│ │  └────────────────────────────────────────────────────────────────┘  │   │
│ │         │                                                             │   │
│ │         ▼                                                             │   │
│ │  ┌────────────────────────────────────────────────────────────────┐  │   │
│ │  │  4. DOWNLOAD PIECES (as fast as peers allow)                   │  │   │
│ │  │     → Receive blocks → assemble pieces                         │  │   │
│ │  │     → Verify piece hash: SHA-256(piece) == expected_hash       │  │   │
│ │  │     → If mismatch: DISCARD piece; ban sending peer             │  │   │
│ │  │     → If valid: write to disk; send HAVE to all connected peers│  │   │
│ │  └────────────────────────────────────────────────────────────────┘  │   │
│ │         │                                                             │   │
│ │         ▼                                                             │   │
│ │  ┌────────────────────────────────────────────────────────────────┐  │   │
│ │  │  5. UPLOAD PIECES (simultaneously with download)               │  │   │
│ │  │     → Choking algorithm: unchoke top-4 peers by upload speed   │  │   │
│ │  │     → Tit-for-tat: peers who upload to you get unchoked        │  │   │
│ │  │     → Optimistic unchoke: 1 random peer every 30s (new peers)  │  │   │
│ │  └────────────────────────────────────────────────────────────────┘  │   │
│ └──────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘

CENTRAL INFRASTRUCTURE (Optional — not required for transfer):
  ┌────────────────┐  ┌─────────────────┐  ┌──────────────────────────────┐
  │    TRACKER     │  │  DHT BOOTSTRAP  │  │  TORRENT INDEXING SITE       │
  │  (HTTP/UDP)    │  │   NODES         │  │  (Search, host .torrent files)│
  │                │  │                 │  │   e.g., ThePirateBay, Nyaa   │
  │  Peer registry │  │  8 well-known   │  │                              │
  │  per infohash  │  │  bootstrap IPs  │  │                              │
  │  Announces     │  │  → join DHT     │  │                              │
  └────────────────┘  └─────────────────┘  └──────────────────────────────┘
```

---

## 5. Deep Dives

### 5.1 File Chunking & Integrity Verification (Merkle Trees)

#### File Splitting Strategy

```
Why chunking?
  Resume downloads: crash after 90% → resume from last verified piece (not restart)
  Parallel download: get different pieces from different peers simultaneously
  Integrity: verify each chunk independently (bad peer only corrupts 1 piece, not whole file)
  Pipeline: request next piece while receiving current one

Piece size selection:
  Too small (e.g., 16 KB):
    → 10 GB file / 16 KB = 655,360 pieces
    → .torrent file: 655,360 × 32B SHA-256 = 20 MB (too large for metadata)
    → More overhead messages (one HAVE per piece)
  
  Too large (e.g., 4 MB):
    → 10 GB / 4 MB = 2,560 pieces
    → If single bad block in piece: must re-download entire 4 MB piece
    → Lower verification granularity

  BitTorrent standard: 256 KB to 2 MB per piece
    Ubuntu ISO (1.4 GB): 512 KB pieces → 2,734 pieces → 87.5 KB hash data in .torrent

  Block size within piece: always 16 KB
    Block = actual TCP request unit
    Why separate? Allows requesting failed blocks from different peers without full piece retry
```

#### SHA-256 Piece Hashes

```
For each piece i: hash[i] = SHA-256(piece_data[i])

Stored in .torrent: concatenation of all piece hashes
  [hash[0] | hash[1] | ... | hash[N-1]]  = N × 32 bytes

Verification on download:
  1. Receive all blocks of piece i from peers
  2. Assemble: piece_data = concat(blocks)
  3. Verify: SHA-256(piece_data) == expected_hash[i]
  4. Match: write piece to disk; mark bitfield[i] = 1
  5. Mismatch: discard entire piece; ban peer who sent the corrupted block
               Request same piece from a different peer

Hash failure handling:
  Single bad block in piece:
    No way to identify WHICH block was bad (only whole-piece hash in .torrent v1)
    → Must re-download entire piece from different peer
    
  BitTorrent v2 improvement: Merkle tree per piece
    → Can identify exact 16 KB block that is corrupted
    → Re-request only that one block (not full piece)
```

#### Merkle Tree for Block-Level Verification (BitTorrent v2)

```
BitTorrent v2 uses SHA-256 Merkle tree for data integrity:

Leaf nodes: SHA-256 hash of each 16 KB block
Internal nodes: SHA-256(left_child | right_child)
Root: Merkle root = unique fingerprint of entire file

For a 512 KB piece with 32 blocks of 16 KB each:

        root (SHA-256)
       /             \
   node_L             node_R
   /    \             /     \
  ...   ...          ...   ...
  (16 internal nodes at each level)
  
  leaf_0     leaf_1    ...    leaf_31
 (hash of  (hash of        (hash of
  block 0)  block 1)        block 31)

Merkle proof for block 5:
  To prove block 5 is valid, provide:
    - hash(block 5) — the leaf
    - sibling hashes along the path to root (log2(32) = 5 hashes)
  
  Client can verify by recomputing root from leaf + proof path
  → Compare with known root (stored in .torrent)
  → If matches: block 5 is authentic

Benefits:
  1. Can verify ANY single 16 KB block independently
  2. No need to re-download full piece on single block failure
  3. Large files (100 GB): Merkle tree built hierarchically across pieces too
  
  File Merkle tree:
    Leaves: piece roots (one Merkle root per piece)
    Internal: SHA-256(left_piece_root | right_piece_root)
    Root: SHA-256 of entire file (infohash = this root in BitTorrent v2)

Infohash in v2:
  v1: SHA-1(bencode(info_dict)) — 20 bytes (vulnerable to SHA-1 collision)
  v2: SHA-256(bencode(info_dict)) — 32 bytes (collision-resistant)
  Backward compat: hybrid torrents have both v1 and v2 infohashes
```

---

### 5.2 Peer Discovery — Tracker vs DHT (Kademlia)

#### Tracker-Based Discovery

```
Tracker: central HTTP/UDP server maintaining peer lists per infohash

Tracker announce protocol (HTTP):
  GET /announce
      ?info_hash={20B infohash URL-encoded}
      &peer_id={20B random client ID}
      &port=6881                       # client's listening port
      &uploaded=1048576                # bytes uploaded this session
      &downloaded=524288               # bytes downloaded
      &left=1048576000                 # bytes remaining
      &event=started                   # started | stopped | completed | (empty=periodic)
      &compact=1                       # want compact peer list (6 bytes/peer vs 50 bytes)
      &numwant=50                      # how many peers to return

  Response (bencoded):
  {
    "interval": 1800,        # announce again in 30 minutes
    "min interval": 900,     # minimum re-announce interval (15 min)
    "complete": 1023,        # seeders (peers with full file)
    "incomplete": 4521,      # leechers (peers still downloading)
    "peers": "<compact format: 6 bytes per peer = 4B IP + 2B port>"
  }
  
  Compact format (50 peers): 50 × 6 = 300 bytes vs dict format 50 × 100B = 5 KB

UDP Tracker (more efficient, ~80% of trackers use this):
  UDP: no TCP handshake overhead; better for high-announce-rate scenarios
  Protocol: 2-step (connection_id exchange for replay protection)
    Step 1: CONNECT request → server returns connection_id (valid 2 minutes)
    Step 2: ANNOUNCE with connection_id → returns peers
    → Prevents UDP spoofing attacks

Tracker limitations:
  - Single point of failure: tracker down → new peers can't find each other
  - Potentially censorable: ISPs/governments block tracker domains
  - Privacy: tracker logs all (IP, infohash) associations
  → Solution: DHT (decentralized, no central authority)
```

#### DHT — Kademlia Distributed Hash Table

```
DHT replaces the tracker with a decentralized network of peers.

Core idea: Every BitTorrent client also participates in the DHT network.
  DHT stores: infohash → list of peers who have that torrent
  No central server: information distributed across all DHT nodes

Kademlia routing:
  Each DHT node has a 160-bit NodeID (random)
  Distance between nodes: XOR of NodeIDs (XOR metric)
    distance(A, B) = A XOR B
    XOR is symmetric: distance(A,B) == distance(B,A)
    XOR is hierarchical: nearby NodeIDs share long common prefix
  
  Each node maintains k-buckets (typically k=8):
    One bucket per bit-prefix distance
    Bucket for distance 2^0: closest 8 nodes
    Bucket for distance 2^1: next 8 nodes
    ...
    Bucket for distance 2^159: nodes at max distance
    Total: 160 buckets × 8 nodes = 1,280 routing table entries per node

DHT operations:

  PING (node liveness check):
    Request:  { type:"q", q:"ping",  a:{id:my_node_id} }
    Response: { type:"r", r:{id:responder_node_id} }

  FIND_NODE (routing table lookup):
    Request:  { q:"find_node", a:{id:my_id, target:target_id} }
    Response: { r:{id:my_id, nodes:<compact 26-byte per-node list>} }
    Returns: k-closest nodes to target that the responder knows

  GET_PEERS (find peers for a torrent infohash):
    Request:  { q:"get_peers", a:{id:my_id, info_hash:infohash} }
    Response (if node has peer list):
              { r:{id:my_id, token:"opaque_token", values:["<ip:port>", ...]} }
    Response (if node doesn't have it):
              { r:{id:my_id, token:"token", nodes:"<8 closest nodes compact>"} }
    
    Iterative algorithm:
      1. Pick 8 DHT nodes from own routing table closest to infohash (by XOR)
      2. Send GET_PEERS to all 8 simultaneously
      3. Collect responses: some return peer lists (→ done!), others return closer nodes
      4. Recurse: send GET_PEERS to newly discovered closer nodes
      5. Stop when nodes are not getting closer to infohash (convergence)
      6. Typically converges in 5-20 hops with O(log N) round trips

  ANNOUNCE_PEER (register yourself as a peer for this infohash):
    Called after GET_PEERS returns token
    Request: { q:"announce_peer", a:{id:my_id, info_hash:infohash,
                                      port:6881, token:"opaque_token"} }
    Token validates: only the IP that received GET_PEERS can announce
    (prevents IP spoofing)

DHT bootstrap:
  New node knows NOTHING about DHT network initially
  Hardcoded bootstrap nodes in client:
    router.bittorrent.com:6881
    router.utorrent.com:6881
    dht.transmissionbt.com:6881
  Step 1: PING bootstrap node → get its NodeID
  Step 2: FIND_NODE(my own NodeID) → discover nearby nodes
  Step 3: Populate own k-buckets → fully bootstrapped in ~30s
```

---

### 5.3 Piece Selection — Rarest First Algorithm

> **Interview tip:** This is the key algorithmic insight of BitTorrent. Explains why file distribution self-scales.

#### The Last-Piece Problem (Why Rarest-First Matters)

```
Naive piece selection: sequential (download piece 0, 1, 2, 3...)

Problem with sequential:
  Many leechers race to download piece 0 first (everyone starts from beginning)
  Piece 0: available at 1000 peers (everyone downloaded it)
  Piece 2000 (near end): available at only 10 peers (few have finished)
  
  The last 10% of the file becomes a bottleneck:
    → Swarm converges on rare pieces only at the end
    → Those 10 peers are overwhelmed with requests for rare pieces
    → "Last piece delay": everyone waits for the same few peers
    → Total swarm throughput DROPS when nearing completion

Solution: Rarest First:
  Always request the piece that the fewest peers have.
  
  Effect: pieces that are rare get distributed early → common quickly
  After a few minutes: ALL pieces are at roughly equal availability
  → No bottleneck at the end; swarm throughput stays high throughout
  
  Mathematical intuition:
    Each piece requested goes to scarcest resource first
    → Entropy of the piece distribution decreases over time
    → System naturally balances toward uniform piece availability
```

#### Rarest-First Implementation

```python
class PieceSelector:
    def __init__(self, total_pieces: int):
        self.total_pieces = total_pieces
        self.piece_availability = [0] * total_pieces   # count of peers with each piece
        self.have_pieces = set()                         # pieces I've downloaded
        self.requested_pieces = set()                    # pieces currently in-flight

    def on_peer_bitfield(self, peer_id: str, bitfield: bytes):
        """Called when peer sends its bitfield after handshake."""
        for i in range(self.total_pieces):
            byte_idx, bit_idx = divmod(i, 8)
            if bitfield[byte_idx] & (0x80 >> bit_idx):
                self.piece_availability[i] += 1

    def on_peer_have(self, peer_id: str, piece_index: int):
        """Called when peer announces they got a new piece."""
        self.piece_availability[piece_index] += 1

    def on_peer_disconnect(self, peer_id: str, peer_bitfield: bytes):
        """Decrement availability when peer leaves."""
        for i in range(self.total_pieces):
            if peer has piece i:
                self.piece_availability[i] -= 1

    def select_next_piece(self, peer: Peer) -> Optional[int]:
        """
        Choose which piece to request from this specific peer.
        Uses rarest-first: prefer pieces with lowest global availability.
        """
        # Candidate pieces: peer has it, AND we don't have it, AND not in-flight
        candidates = [
            i for i in range(self.total_pieces)
            if peer.has_piece(i)
               and i not in self.have_pieces
               and i not in self.requested_pieces
               and self.piece_availability[i] > 0
        ]

        if not candidates:
            return None

        # Rarest first: sort by availability (ascending = rarest first)
        # Tiebreak: random (to avoid all peers picking the same piece)
        min_availability = min(self.piece_availability[i] for i in candidates)
        rarest_candidates = [i for i in candidates
                             if self.piece_availability[i] == min_availability]

        # Random selection among equally rare pieces
        # → Different peers request different pieces → maximizes swarm diversity
        chosen = random.choice(rarest_candidates)
        self.requested_pieces.add(chosen)
        return chosen

    def special_cases(self):
        """
        Special case 1: Initial random selection
          Problem: All pieces start with 0 availability on first connect.
          Solution: Pick first 4 pieces randomly → get something to trade fast.
          
        Special case 2: End-game mode
          When < 10 pieces remain (all very close to done):
          → Request same remaining pieces from ALL connected peers simultaneously
          → First response wins (cancel others)
          → Prevents: single slow peer being the bottleneck for final pieces
          
        Special case 3: Streaming mode (for video players)
          Instead of rarest-first: sequential (prioritize next needed piece)
          Trade-off: worse for swarm health, better for playback continuity
        """
```

---

### 5.4 Choking/Unchoking — Tit-for-Tat Fair Exchange

> **Interview tip:** This game theory design is what prevents freeloaders and why BitTorrent actually works in practice.

#### The Free Rider Problem

```
Without incentive mechanism:
  Leecher A:  Downloads 1 GB; uploads 0 bytes (just takes, never gives)
  Leecher B:  Downloads 1 GB; uploads 500 MB (contributes)
  
  Why would B continue uploading to A? A never gives anything back.
  If everyone acts like A → nobody uploads → swarm dies.

Solution: Tit-for-tat incentive mechanism via choking
  Analogy: I'll share with you if and only if you share with me.
  Implementation: client CHOKES (refuses upload) to peers who don't reciprocate.
```

#### Choking Algorithm

```
Every peer maintains:
  upload_slots: N unchoked peers (can receive uploads from me)
                Standard: 4 regular unchoke slots
  optimistic_unchoke: 1 additional slot (for exploration)

Standard unchoke (run every 10 seconds):
  1. Measure upload rates from each peer TO ME (last 20 seconds rolling avg)
  2. Sort peers by their upload rate to me (descending) — who's downloading fastest from me
     Wait: "by their upload rate TO ME" = how fast are they uploading to me?
     No — correct: sort by how fast I can upload TO THEM given bandwidth constraints
     
  Actually: Sort peers by download rate FROM THEM to me:
    → Peers who upload the most to me → I unchoke them (reward good uploaders)
  
  3. Unchoke top-4 peers by download_rate_from_them
  4. Choke all other peers (even if currently unchoked)
  
  If peer is unchoked but declares NOT_INTERESTED:
    → They don't need our pieces (maybe they're more complete than us)
    → Free up that unchoke slot for an INTERESTED peer

  If we are a seeder (100% complete):
    → We have nothing to download from peers → can't measure "how much they upload to me"
    → Use upload-rate-to-them instead: unchoke peers we've been SENDING to most
    → Reward peers who are active in the swarm

Optimistic Unchoke (run every 30 seconds):
  Purpose: Discover new high-quality peers; give new peers a chance
  
  Select 1 random peer from CHOKED + INTERESTED peers
  Unchoke them for 30 seconds regardless of their upload rate
  
  Why? 3 reasons:
    1. New peers just joined: upload rate from them = 0 (they haven't had time)
       Without optimistic unchoke: new peers never get unchoked → can't download → leave
    2. Bootstrap: peers need pieces to reciprocate; optimistic gives them a start
    3. Exploration: maybe choked peer has great bandwidth → test periodically
  
  Every 30s: cycle to a different random peer (gives everyone a chance)

Tit-for-tat results in practice:
  High-bandwidth peers → top of sort → unchoked by many → download fast → upload fast → reward loop
  Low-bandwidth peers → unchoked by fewer → download slower (but still get content via optimistic)
  Freeloaders (upload nothing) → never in top-4 → always choked → download very slowly
  → Self-enforcing: the system punishes free-riders without any central authority
```

#### Choke Message Flow Example

```
Alice, Bob, Carol, Dave all downloading same torrent.
Alice has: [piece 0, 1, 3, 5] (partial)
Bob   has: [piece 0, 1, 2, 4] (partial; has piece 2 which Alice needs)
Carol has: [piece 0, 2, 3, 6] (partial)
Dave  has: [piece 0, 1, 2, 3, 4, 5, 6, 7] (seeder)

Alice's perspective (every 10 seconds):
  Download rate from Bob in last 20s:    500 KB/s
  Download rate from Carol in last 20s:  300 KB/s
  Download rate from Dave in last 20s:   800 KB/s
  Alice unchokes: Dave (800), Bob (500), Carol (300) [top-3 of available peers]

Alice's CHOKE/UNCHOKE messages:
  → UNCHOKE to Dave, Bob, Carol
  Alice can now request pieces from those who have pieces she needs AND are unchoked
  Alice sends REQUEST for piece 2 to Bob (he has it; he unchoked Alice); Download!

Bob's perspective:
  If Alice is uploading fast to Bob → Bob unchokes Alice (tit-for-tat)
  Both benefit: mutually unchoking (bi-directional transfer)
  
  If Alice stops uploading to Bob (e.g., Alice has nothing Bob needs):
    → Bob's sort next cycle: Alice's upload to Bob = 0 KB/s
    → Alice drops out of Bob's top-4
    → Bob CHOKES Alice after 10s (no longer unchoked)
    → Alice must find other peers to get fast downloads
```

---

### 5.5 NAT Traversal & Connectivity

```
Problem: Most peers are behind NAT (home routers, mobile carriers, corporate firewalls)
  Peer A: 192.168.1.5 behind router (public IP: 1.2.3.4)
  Peer B: 10.0.0.8 behind router (public IP: 5.6.7.8)
  
  B tries to connect to A's tracker-reported address (1.2.3.4:6881):
  → Router at 1.2.3.4 has NO port forwarding rule for 6881
  → Router drops the incoming SYN → connection fails
  → B can't reach A

Common: ~70% of BitTorrent peers are behind NAT → connectivity important
```

#### NAT Traversal Techniques

```
Technique 1: UPnP (Universal Plug and Play) Port Mapping
  Client asks home router automatically: "Please forward port 6881 to me"
  Router creates NAT mapping: 1.2.3.4:6881 → 192.168.1.5:6881
  Works for: home routers (most support UPnP)
  Doesn't work: corporate firewalls, mobile carrier-grade NAT

Technique 2: NAT Hole Punching (most common P2P technique)
  Concept: both peers simultaneously try to connect to each other
           through their NAT → each NAT opens a hole for outgoing traffic
           → incoming traffic from the other peer uses the same hole

  Setup: requires a third party (STUN/relay server) to coordinate timing
  
  Step 1: Peer A connects to STUN server: "What's my public IP:port?"
           STUN: tells A "your public endpoint is 1.2.3.4:54321"
           Simultaneously: Peer B gets its public endpoint "5.6.7.8:12345"
  
  Step 2: A tells tracker/DHT: "My public endpoint is 1.2.3.4:54321"
           B learns A's endpoint from tracker/DHT (peer list)
  
  Step 3: Simultaneous connection (hole punch):
           A sends UDP to 5.6.7.8:12345 → B's NAT creates outgoing entry
           B sends UDP to 1.2.3.4:54321 → A's NAT creates outgoing entry
           A's UDP arrives at B's NAT → B's NAT sees it matches outgoing entry → PASS!
           B's UDP arrives at A's NAT → same → PASS!
           → Both NATs now have "holes" in both directions → connection established
  
  Works for: ~65% of NAT configurations (cone NATs)
  Fails for: symmetric NAT (different source port for each destination)

Technique 3: TURN Relay (fallback)
  When NAT traversal fails completely:
  Both peers connect to a TURN relay server
  Relay server forwards traffic between them
  Cost: relay server bears all bandwidth → expensive
  Use: last resort (< 10% of connections need this)

BitTorrent Extension: µTP (Micro Transport Protocol)
  Built on UDP; implements TCP-like reliability on top
  Congestion-friendly: uses less bandwidth than TCP when network is shared
  Allows: UDP-based BitTorrent traffic (easier NAT traversal than TCP)
  DHT uses UDP; µTP uses UDP → most modern clients prefer UDP paths

Firewall traversal:
  ISPs deep-packet-inspect (DPI) and throttle BitTorrent traffic
  Solution: Protocol Encryption (obfuscation)
    → RC4-based stream cipher makes traffic look like random bytes
    → DPI can't identify it as BitTorrent
    → Not truly secure encryption (key exchanged in plain, no authentication)
    → Just obfuscation to avoid ISP throttling
```

---

## 6. Scale & Resilience

### Swarm Health & Seeder Preservation

```
Critical invariant: as long as 1 complete copy exists → file can be distributed.
Danger: if all leechers die before any becomes a seeder → swarm collapses.

Swarm states:
  Healthy: seeders > 0; any leecher can complete
  Degraded: no seeders; partial copies among leechers
    → If leechers collectively have all pieces: download possible but slow
    → If some piece is MISSING from all peers: that piece is GONE FOREVER
  Dead: no peers at all (even partial)

Mitigation strategies:

Strategy 1: Seeder incentives
  Torrent sites display seeder:leecher ratio ("health indicator")
  Community norms: stay seeded for at least 1:1 ratio (upload as much as you downloaded)
  Some private trackers enforce this: ban accounts with ratio < 1.0

Strategy 2: Web Seeds (HTTP fallback)
  .torrent file includes "url-list" of HTTP servers:
  "url-list": ["https://ubuntu.com/release/ubuntu-22.04.iso"]
  
  If swarm has no seeders: client falls back to HTTP download from official server
  When swarm recovers (new seeders appear): switch back to P2P
  → Ensures file is ALWAYS downloadable (via CDN if swarm is dead)

Strategy 3: Distributed tracker with peer exchange (PEX)
  PEX: peers share peer lists with each other (extension ut_pex)
  → Even if tracker is down: peers already connected to a few others
    can discover more peers through their connections
  → 6 degrees of separation: high connectivity even without tracker

Strategy 4: Archival seeders
  Organizations (Internet Archive, Academic institutions) run permanent seeders
  For important content: multiple well-provisioned seeders ensure longevity
```

### Performance Optimizations

```
1. Piece request pipelining:
   Don't wait for block response before sending next request
   Keep 5-10 outstanding block requests per peer (fill TCP window)
   → Hides round-trip latency; maintains full bandwidth utilization
   
   Without pipelining: (request → wait → receive → request → wait → receive)
     500ms RTT × 1 block at a time = 16KB / 0.5s = 32 KB/s (terrible!)
   
   With 10 outstanding: 10 × 16KB in flight = 160KB per RTT
     160KB / 0.5s = 320 KB/s per peer (much better)
   
   With 5 peers pipelined: 5 × 320 KB/s = 1.6 MB/s → approaches link capacity

2. Endgame mode:
   Last few pieces: request from ALL peers simultaneously (not just best ones)
   Cancel duplicate when first response arrives
   → Prevents "straggler" problem where 1 slow peer holds up completion

3. Disk I/O optimization:
   Pre-allocate file space on disk (or use sparse files)
   Write pieces asynchronously (don't block download while writing)
   Read pieces directly from mmap (memory-mapped files) for upload
   → Avoids disk I/O blocking network I/O

4. Super-seeder mode (for initial distribution):
   Only sends each peer a unique piece they don't already have
   → Maximizes diversity of piece distribution across the swarm
   → Gets file into the swarm faster when initial seeder is bottleneck
```

### Tracker Architecture (for non-DHT deployments)

```
High-load tracker:
  27,778 announces/sec (from capacity estimate)
  
  Stateless tracker design (no sessions):
    Each announce is independent (client sends all state: uploaded, downloaded, left)
    → Horizontal scaling: any tracker node handles any announce
  
  Data model per infohash:
    peers: sorted set { peer_id → {ip, port, uploaded, downloaded, left, last_seen} }
    Expiry: remove peers not announced in > 45 min (they've left the swarm)
  
  Storage: Redis Cluster
    100K active torrents × 500 peers × 100 bytes = 5 GB → fits in Redis
    
  API rate limiting: each peer: 1 announce per 15 min (enforced by tracker)
    Bandwidth: 27K announces/sec × 300 bytes = ~8 MB/s inbound, 20 MB/s response
    → Single server can handle with UDP tracker protocol
  
  Peer selection for response:
    Return random 50 peers from the swarm (not the same 50 every time)
    → Need diversity: different peers have different pieces
    → Uniform random sampling with reservoir sampling for large swarms
```

---

## 7. Trade-offs & Alternatives

| Decision | Choice | Alternative | Reason |
|---|---|---|---|
| **Piece selection** | Rarest first | Sequential | Rarest: balances piece distribution → no end-game bottleneck; Sequential: better for streaming |
| **Upload incentive** | Tit-for-tat choking | Equal bandwidth to all | Tit-for-tat: punishes free-riders; equal: no punishment → swarm degrades under free-riders |
| **Peer discovery** | DHT (Kademlia) + Tracker | Tracker only | DHT: decentralized, no SPOF, censorship-resistant; Tracker: simpler, faster peer find |
| **Data integrity** | SHA-256 per piece | No verification | No verification: corrupted data accepted silently; SHA-256: tamper-proof, detects bad peers |
| **Block-level integrity** | Merkle tree (v2) | Whole-piece hash only (v1) | Merkle: identify exact bad 16 KB block; v1: must re-download full piece on any corruption |
| **Transport** | µTP over UDP + TCP | TCP only | µTP: congestion-friendly, better NAT traversal; TCP: simpler, universal support |
| **NAT traversal** | UDP hole punching | TURN relay always | Hole punching: no server cost; works 65% of time; TURN: 100% works but server-side cost |
| **Infohash** | SHA-256 v2 (32 bytes) | SHA-1 v1 (20 bytes) | SHA-1 has known collisions since 2017; SHA-256 collision-resistant |
| **Choke frequency** | Every 10s (re-evaluate) | Every 30s | 10s: faster adaptation to changing bandwidth; 30s: less overhead, more stable |

### BitTorrent vs Alternative P2P Systems

| System | Incentive | Integrity | Peer Discovery | Best For |
|---|---|---|---|---|
| **BitTorrent** | Tit-for-tat choke | SHA-256 Merkle | Tracker + DHT | Large file distribution |
| **IPFS** | Bitswap ledger (credits) | Content addressing (CID) | DHT (libp2p) | Permanent content-addressed storage |
| **WebRTC** | None (CDN-hybrid) | None | Signaling server | Browser-based P2P, streaming |
| **Freenet** | Automatic caching | SHA-1 | Darknet (trust list) | Anonymous publishing |
| **Gnutella** | None | None | Flooding | Historical; poor at scale |

---

## 8. SDE 3 Signals Checklist

```
REQUIREMENTS
  [ ] Knew BitTorrent vocabulary (seeder, leecher, piece, block, choke, swarm, DHT)
  [ ] Distinguished .torrent file (with tracker) from magnet link (DHT-based)
  [ ] Mentioned tit-for-tat incentive mechanism proactively
  [ ] Asked about NAT traversal (70% of peers are behind NAT)

CAPACITY
  [ ] 10 GB file to 10M users: CDN = $358K cost; BitTorrent = ~$0 (for content owner)
  [ ] Piece size math: 10 GB / 512 KB = 20,480 pieces → 655 KB hash data
  [ ] Swarm upload capacity: 10M users × 1 Mbps upload = 10 Tbps (dwarfs any CDN)
  [ ] Tracker load: 27K announces/sec → Redis-backed tracker handles on single node

ARCHITECTURE
  [ ] Three discovery methods: tracker + DHT + PEX (fallback chain)
  [ ] Four-step download: discover → connect+handshake → piece select → download+verify
  [ ] Simultaneous download and upload (leechers are peers in both directions)
  [ ] Web seeds as HTTP fallback when swarm is dead

DEEP DIVES

  Chunking & Integrity:
    [ ] Piece size trade-off: too small = large .torrent; too large = expensive retries
    [ ] Block (16 KB) vs Piece (512 KB): block = TCP request unit; piece = verification unit
    [ ] SHA-256 per piece: hash mismatch = discard + ban peer + re-request
    [ ] Merkle tree (v2): identify exact corrupt 16 KB block without full piece re-download
    [ ] v2 infohash = Merkle root of file → collision-resistant SHA-256

  DHT / Kademlia:
    [ ] XOR metric for distance (symmetric, hierarchical)
    [ ] k-buckets: 160 buckets × 8 nodes = 1280 routing table entries
    [ ] GET_PEERS: iterative lookup converges in O(log N) hops
    [ ] ANNOUNCE_PEER: token prevents IP spoofing
    [ ] Bootstrap: 3 hardcoded well-known nodes → populate routing table in 30s

  Rarest First:
    [ ] Problem it solves: sequential = last 10% bottleneck
    [ ] Mechanism: count availability per piece; request lowest availability first
    [ ] Ties broken randomly → different peers request different pieces
    [ ] End-game mode: last <10 pieces → request from ALL peers; cancel duplicates
    [ ] Streaming exception: sequential mode for video playback continuity

  Tit-for-Tat Choking:
    [ ] 4 regular unchoke slots + 1 optimistic unchoke slot
    [ ] Re-evaluate every 10s: sort by download rate FROM them
    [ ] Optimistic unchoke every 30s: random choked+interested peer → discover/bootstrap
    [ ] Seeder mode: sort by upload rate TO them (can't measure downloads from complete peer)
    [ ] Result: free-riders get choked by everyone → very slow downloads → natural deterrent

  NAT Traversal:
    [ ] 70% of peers behind NAT → not optional to handle
    [ ] UPnP: automatic port forwarding (home routers)
    [ ] UDP hole punching: simultaneous connect through NAT; 65% success rate
    [ ] STUN: discover your own public endpoint
    [ ] TURN: relay server (last resort; expensive; 100% works)
    [ ] Protocol encryption (RC4 obfuscation): defeat ISP DPI/throttling

SCALE & RESILIENCE
  [ ] Swarm death prevention: web seeds (HTTP fallback) + seeder incentives + PEX
  [ ] Piece pipelining: 10 outstanding requests per peer → saturate bandwidth
  [ ] Super-seeder mode: only sends unique pieces → maximizes early diversity
  [ ] Tracker: Redis-backed stateless; 27K announces/sec on single node

TRADE-OFFS
  [ ] Rarest-first vs sequential (swarm health vs streaming continuity)
  [ ] Tit-for-tat vs equal bandwidth (free-rider prevention vs simplicity)
  [ ] DHT vs tracker (decentralization/censorship-resistance vs peer discovery speed)
  [ ] SHA-256 Merkle (v2) vs SHA-1 piece hash (v1) (security vs compatibility)
  [ ] µTP vs TCP (congestion-friendliness vs universal support)
```
