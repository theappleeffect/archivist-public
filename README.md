A Fabric mod for Minecraft that automatically detects and logs server plugins, software, and metadata.     
                                                                                                             
  Website: https://archivist-web.net                                                                         
  Docs: https://archivist-web.net/docs                                                                       
  Discord: https://discord.gg/archivist                                                                    

  What it does

  When you join a server, Archivist passively identifies what plugins, server software, and configuration it's
  running. Results are logged locally and can optionally be uploaded to the Archivist db or other databases.
  
  **PRESS (Z) to open the mod menu:**
  <img width="1919" height="1079" alt="image" src="https://github.com/user-attachments/assets/2729be6d-d605-4ce1-8084-a55253ef07ba" />

  Detection
  - Identifies plugins through command trees, plugin channels, namespaces, brand strings, and GUI
  fingerprinting
  - Detects server software and version through brand strings and version probes
  - Plugin command glossary for tracking and resolving unknown commands
  - Filters out client-side mod namespaces to avoid false positives
  - Late namespace detection for plugins that register after the initial scan
  - Lobby vs. real server confidence scoring to avoid logging hub/lobby data

  Automation
  - Multi-server scanning: provide a list of servers and Archivist connects, scans, logs, and moves to the
  next one automatically
  - Lobby navigation: hotbar clicking, GUI walking, and NPC interaction to get past hub servers
  - Auth/captcha detection: auto-disconnects from servers requiring /login, /register, or captcha solving
  - Anti-VPN / suspicious kick detection: recognizes and skips servers that reject the connection
  - Configurable delays, retries, backoff, and proxy support

  GUI
  - Full in-game interface with plugin lists, server info, console, detection report, and scan overlay
  - Two layout modes: dynamic (sidebar with detachable panels) and windowed (draggable windows with taskbar)
  - Server exclusions: skip logging for specific servers (your own, private servers, etc.)
  - Exceptions list: anonymize server IPs while still resolving through tab and scoreboard
  - Custom font support: load your own .ttf files
  - GUI inspector for analyzing server inventory screens
  - Account manager and proxy manager built in

  Upload
  - Automatic or manual upload to the Archivist website or your own custom API endpoints
  - Offline queue: failed uploads are retried automatically when connectivity returns
  - Per-endpoint configuration with custom auth headers

  Supported Versions

  1.21 - 1.21.11 (Fabric)
