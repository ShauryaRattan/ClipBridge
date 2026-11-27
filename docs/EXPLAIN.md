# Understanding ClipBridge: for the review

You must be able to explain your code. Read this once, then open each file and find the things named here.

## 1. Which Java concepts are used, and where

| Concept | Where to point |
|---|---|
| **Classes, interfaces, polymorphism** | `ClipboardBackend` is an interface with two implementations: `SystemClipboardBackend` (the real clipboard) and `FileClipboardBackend` (a file, for tests and the one-computer demo). `Node` only knows the interface. |
| **Encapsulation, nested classes, enums** | `Node` keeps its state private; `Node.Target`, `Node.Connection`, `Node.Hello` are nested classes; `Node.State` is an enum. |
| **Exception handling** | Custom checked exceptions `Refused` and `SelfConnection` in `Node`; `try/catch/finally` and try-with-resources; `IllegalArgumentException` with a plain message for bad input (`connectTo`, `setPairingCode`); the GUI shows the message in the dialog. |
| **Collections** | `ConcurrentHashMap` (targets, live connections), `CopyOnWriteArrayList` (listeners), `ArrayList`, `HashSet`, `Comparator` for sorting the computer list. |
| **Multithreading** | `ExecutorService` thread pool for connections, `ScheduledExecutorService` for the clipboard poll, reconnect loop and heartbeat; one sender thread per connection; `synchronized`, `volatile`, `AtomicBoolean`/`AtomicInteger`; daemon threads; the Swing thread is respected with `SwingUtilities.invokeLater`. |
| **File handling** | `java.nio.file.Files`: settings in `config.properties` (`java.util.Properties`), history file, atomic "write a temp file, then move it over". |
| **Serialization** | `HistoryEntry implements Serializable`; `History` writes an `ArrayList` with `ObjectOutputStream` and reads it back with `ObjectInputStream`, protected by an `ObjectInputFilter` that accepts only our own class. A damaged file gives an empty history, not a crash. |
| **Socket programming** | TCP: `ServerSocket`/`Socket` with length-prefixed frames over `DataInputStream`/`DataOutputStream`. UDP: `MulticastSocket` for discovery. Timeouts (`setSoTimeout`) and heartbeats detect dead links. |
| **Cryptography** | `javax.crypto`: AES-256-GCM (`Cipher`), PBKDF2 (`SecretKeyFactory`), HMAC-SHA256 (`Mac`), `SecureRandom`. |
| **Swing / AWT GUI** | `JFrame`, `JMenuBar`, `JSplitPane`, `JTable` with an `AbstractTableModel` and a custom cell renderer, `JList` with a `ListCellRenderer`, modal `JDialog`s, `SystemTray`/`TrayIcon`, `GridBagLayout`/`BorderLayout`/`FlowLayout`. |

## 2. Walk through one copy (say this out loud)

1. You press Ctrl+C on computer A. `Node.pollClipboard` (runs every 400 ms) reads the clipboard and sees new text.
2. `publishLocal` puts it in the history and calls `sendAsync` on every connection.
3. `Connection.sendAsync` seals the message with `SecureChannel.seal` (AES-GCM) and writes one frame to the socket.
4. On computer B, `Connection.readLoop` reads the frame and calls `SecureChannel.open`. If it does not verify, the link is closed.
5. `receiveRemote` puts the text on B's clipboard and remembers it as `lastText` so B's own poll does not send it back.
6. The `Node.Listener` tells the window, which refreshes the history list and the tray shows a pop-up.

## 3. Questions you may be asked

**Why two kinds of sockets?** TCP for the clipboard data because it must arrive complete and in order. UDP multicast for discovery because it is a tiny "I am here" message that anyone on the network can hear without knowing the other computer's address.

**Why is there a pairing code, and how is the key made?** The code is a shared secret. PBKDF2 (120,000 rounds of HMAC-SHA256) turns it into a 256-bit key, which makes guessing slow. For each connection a fresh *session key* is made from that key and two random nonces, so no two connections ever use the same key.

**Why AES-GCM and not just AES?** GCM encrypts *and* authenticates: any change to a message is detected. Plain AES would let an attacker flip bits without us noticing.

**How do you stop replay attacks?** The IV is built from a direction number and a message counter that both sides count themselves and never send. A replayed or reordered message was sealed with a different counter than the receiver expects, so it fails to decrypt and the link is closed. Direction numbers also stop a message being reflected back to its sender.

**What happens if the wrong code is entered?** The handshake proof does not decrypt, the receiver sends a `REFUSE` frame and closes, and both screens say "X has a different pairing code". No clipboard text has been exchanged at that point.

**What if both computers connect to each other at the same time?** Both would create a link. Both apply the same rule (keep the link started by the computer with the smaller random id) and close the other, so exactly one remains. There is a test for it (five rounds).

**How does it avoid an endless loop (A sends to B, B sends back to A...)?** When B writes received text to its clipboard it stores it in `lastText` under a lock, so its own poll sees "same as before" and does not send it.

**What if the network drops?** The read has a 30 s timeout and a heartbeat is sent every 10 s, so a dead link is noticed. A reconnect loop runs every 0.5 s and dials every known computer that is not connected (every 1.5 s after a drop, every 5 s if unreachable). `AllTests` has "a cut connection comes back by itself".

**Why a `FileClipboardBackend`?** Two copies of the app on one computer would share the one real clipboard, which makes a demo and the tests confusing. A file as a clipboard lets each copy have its own. It also made the tests possible without a screen.

**How do you handle large text?** A limit of 1 MB. A bigger copy is not sent, and the user is told in the status bar ("too large to share"). A frame length above the limit from the network is rejected, so a stranger cannot make the program allocate huge memory.

**Is it secure against everything?** No. See "What the encryption protects, and what it does not" in the README: anyone with the code can read the clipboard; the code and history are stored unencrypted on your own disk.

**How was it tested?** 61 automated tests in `AllTests.java` run without any library. They cover the pairing code, the encryption (replay, reorder, tamper, wrong key), history and serialization (including damaged files), settings, the file clipboard under concurrent use, and real network behaviour between 2 and 3 nodes: sync both ways, no echo, refusal of wrong/no code, reconnect, simultaneous dialling, oversize copies, garbage and forged data from a stranger. The window itself was checked by opening it and looking at it.

## 4. Things to change so it is clearly *yours*

- Name, version and author: `Branding.java`, `build.bat`, `installer/ClipBridge.iss` (search for "ClipBridge").
- Logo: edit colours and shapes in `tools/make_logo.py`, then run `python tools/make_logo.py`.
- Colours and wording in the window: `MainWindow.java` (the constants at the top).
