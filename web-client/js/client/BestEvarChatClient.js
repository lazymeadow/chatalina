import {UserManager} from "../users";
import {Logger, NotificationManager, Settings, SoundManager} from "../util";
import {Alert, MessageLog} from "../components";
import {CLIENT_VERSION, INITIAL_RETRIES} from "../lib";
import {AdminTools, ModTools} from "../components/Tools";


export class BestEvarChatClient {
    _roomManager;
    _mainMenu;
    _ready = false

    constructor(hostname = process.env.BEC_SERVER, secure = (process.env.NODE_ENV === 'production'), routingPath = 'chat') {
        this._hostname = `${secure ? 'wss' : 'ws'}://${hostname}/${routingPath}`;

        Settings.init();

        this._messageLog = new MessageLog();
        this._soundManager = new SoundManager();
        this._userManager = new UserManager(this, this._messageLog, this._soundManager);
        this._notificationManager = new NotificationManager((alertData) => new Alert(alertData));
        this._disconnectedAlert = null;
        this._reconnectAlert = null;
        this._reconnectTimeout = null;
        this._reconnectCount = 0;
        this._unreadMessageCount = 0;
        this._reconnectEnabled = true;
    }

    // Public functions

    connect() {
        this._sock = new WebSocket(this._hostname);

        this._sock.onopen = () => {
            window.clearTimeout(this._reconnectTimeout);
            this._reconnectEnabled = true;
            this._reconnectCount = 0;
            if (this._disconnectedAlert) {
                this._disconnectedAlert.remove();
                this._disconnectedAlert = null;
            }
            this._send({
                'type': 'version',
                'client version': CLIENT_VERSION
            });
        };
        this._sock.onmessage = (message) => this._handleMessage(JSON.parse(message.data));
        this._sock.onclose = (event) => {
            if (!event.wasClean) {
                console.error(event);
            }
            this.disconnect(event.code === 3000);
        }
        this._sock.onerror = (event) => console.error(event);

        Logger.set_socket(this._sock);
    }

    disconnect(logout = false) {
        console.log('Bye!');
        this._ready = false;
        if (logout) {
            if (this._sock.readyState === this._sock.OPEN) this._sock.close(1000);
            location.replace('/logout');
        } else if (this._reconnectEnabled) {
            this._attemptReconnect();
        }
    }

    selectGeneralRoom() {
        this._roomManager.setActiveRoom(0);
    }

    _getTitle() {
        let name;
        if (Settings.activeLogType === 'thread') {
            name = this._userManager.getActiveThreadName();
        } else {
            name = this._roomManager.getActiveRoomName();
        }
        return Settings.tabTitle || `${name} | ${process.env.BEC_TITLE || 'Chat'} ${CLIENT_VERSION}`;
    }

    setWindowTitle() {
        document.title = this._getTitle();
    }

    _incrementUnreadMessageCount() {
        if (!document.hasFocus()) {
            this._unreadMessageCount++;
            document.title = `(${this._unreadMessageCount}) ${this._getTitle()}`;
            $("#favicon").attr("href", "/favicon2.png");
        }
    }

    sendMessageNotification(title, body, which_cat) {
        if (this._unreadMessageCount > 1) {
            body = `${this._unreadMessageCount} new messages`
        }
        this._notificationManager.sendMessageNotification(title, body, which_cat);
    }

    disableNotifications() {
        this._notificationManager.disableNotifications();
    }

    enableNotifications() {
        this._notificationManager.enableNotifications();
    }

    reprintLog() {
        if (Settings.activeLogType === 'room') {
            this._roomManager.setActiveRoom(Settings.activeLogId);
        } else {
            this._userManager.setActiveThread(Settings.activeLogId);
        }
    }

    resetUnreadMessageCount() {
        this._unreadMessageCount = 0;
        this.setWindowTitle();
        $("#favicon").attr("href", "/favicon.png");
    }

    sendChat(messageText) {
        if (Settings.activeLogType === 'room') {
            this._send({
                'type': 'chat message',
                'message': messageText,
                'room id': parseInt(Settings.activeLogId, 10)
            });
        } else {
            this._send({
                'type': 'private message',
                'message': messageText,
                'recipient id': Settings.activeLogId
            });
        }
    }

    updateUserList() {
        this._userManager.updateUserList();
    }

    sendIdle(shouldBeIdle) {
        const isIdle = this._userManager.getUserStatus(Settings.userId) === 'idle';
        if (isIdle === undefined) {
            return;
        }
        if ((shouldBeIdle && !isIdle) || (!shouldBeIdle && isIdle)) {
            this._send({
                'type': 'status',
                'status': shouldBeIdle ? 'idle' : 'active'
            });
        }
    }

    sendTyping() {
        const isTyping = this._userManager.getUserTypingStatus(Settings.userId);
        // skip sending if we don't get a "real" value back
        if (isTyping === undefined) {
            return;
        }
        const shouldBeTyping = $('#chat-bar').children('input').val().length > 0;

        let newTyping = (Settings.activeLogId !== Settings.userId && shouldBeTyping) ? Settings.activeLogId : null;
        if (isTyping !== newTyping) {
            this._send({'type': 'typing', 'status': newTyping});
        }
    }

    sendImage(imageUrl, nsfw) {
        const roomId = Settings.activeLogId;
        this._send({
            'type': 'image',
            'nsfw': nsfw,
            'room id': roomId,
            'image url': imageUrl,
        });
    }

    sendImageUpload(imageData, imageType, nsfw) {
        const roomId = Settings.activeLogId;
        this._send({
            'type': 'image upload',
            'image type': imageType,
            'nsfw': nsfw,
            'room id': roomId,
            'image data': imageData,
        });
    }

    submitBug(bugData) {
        this._send({
            'type': 'bug',
            ...bugData
        });
    }

    submitFeature(featureData) {
        this._send({
            'type': 'feature',
            ...featureData
        });
    }

    joinRoom(roomId, accept = true) {
        this._send({
            'type': 'room action',
            'action': 'join',
            'room id': roomId,
            accept
        });
    }

    requestToolList(toolSet) {
        this._send({
            'type': 'tool list',
            'tool set': toolSet
        })
    }

    requestData(dataType) {
        this._send({
            'type': 'data request',
            'data type': dataType
        })
    }

    sendAdminRequest(requestType, data) {
        this._send({
            'type': 'admin request',
            'request type': requestType,
            data
        })
    }

    // Private functions

    _send(data) {
        if (this._sock && this._sock.readyState === 1) {  // SockJS.OPEN
            this._sock.send(JSON.stringify({
                'user id': Settings.userId,
                ...data
            }));
        }
    }

    _handleMessage({data: {data: messageData, type: messageType}}) {
        if (messageType === 'auth fail') {
            this._reconnectEnabled = false;
            this.disconnect(true);
        } else if (messageType === 'room data') {
            if (this._ready) {
                this._receivedRoomData(messageData);
            } else {
                this._cachedRooms = messageData
                this._tryInitData()
            }
        } else if (messageType === 'private message data') {
            this._cachedThreads = messageData
            this._tryInitData()
        } else if (messageType === 'user list') {
            if (this._ready) {
                this._receivedUserList(messageData);
            } else {
                this._cachedUsers = messageData
                this._tryInitData()
            }
        } else if (messageType === 'update') {
            this._receivedUpdate(messageData);
        } else if (messageType === 'chat message') {
            this._receivedChatMessage(messageData);
        } else if (messageType === 'private message') {
            this._receivedPrivateMessage(messageData);
        } else if (messageType === 'alert') {
            this._receivedAlert(messageData);
        } else if (messageType === 'invitation') {
            this._receivedInvitation(messageData);
        } else if (messageType === 'tool list') {
            this._receivedToolList(messageData);
        } else if (messageType === 'data response') {
            this._receivedToolData(messageData);
        } else if (messageType === 'tool confirm') {
            this._receivedToolConfirm(messageData);
        }
    }

    _tryInitData() {
        if (!!this._cachedUsers && !!this._cachedRooms && !!this._cachedThreads) {
            this._receivedUserList(this._cachedUsers)
            this._receivedRoomData(this._cachedRooms)
            this._receivedPrivateMessageData(this._cachedThreads)
            this._ready = true
            this._cachedUsers = null
            this._cachedRooms = null
            this._cachedThreads = null
        }
    }

    _receivedRoomData({rooms, all, 'clear log': clearLog}) {
        this._roomManager.addRooms(rooms, all, clearLog);
    }

    _receivedUserList({users}) {
        this._userManager.updateUserList(users);
    }

    _receivedPrivateMessageData({threads}) {
        this._userManager.addPrivateMessageThreads(threads);
    }

    _receivedUpdate(messageData) {

        $.each(messageData, (key, value) => {
            if (key === 'permission') {
                if (Settings.permission !== value) {
                    Settings.permission = value;
                    if (this._mainMenu) {
                        this._mainMenu.redraw();
                    }
                }
            } else {
                Settings[key] = value;
                if (key === 'volume') {
                    SoundManager.updateVolume();
                }
                if (key === 'soundSet') {
                    this._soundManager.updateSoundSet();
                }
            }
        });
    }

    _receivedChatMessage({'room id': roomId, ...messageData}) {
        this._roomManager.addMessage(messageData, roomId);
        this._incrementUnreadMessageCount();
    }

    _receivedPrivateMessage(messageData) {
        this._userManager.addMessage(messageData);
        this._incrementUnreadMessageCount();
    }

    _receivedInvitation({'room id': roomId, message}) {
        new Alert({
            id: `invite-${roomId}`,
            content: message,
            type: 'actionable',
            actionText: 'Join!',
            actionCallback: () => this.joinRoom(roomId, true),
            dismissText: 'No, thanks.',
            dismissCallback: () => this.joinRoom(roomId, false)
        });
        this._incrementUnreadMessageCount();
    }

    _receivedAlert({id, message, ...alertProps}) {
        let dismissCallback;
        let elementId
        if (!!id) {  // this is a persistent message, tell the server to get rid of it, too
            dismissCallback = () => {
                this._send({type: 'remove alert', id});
            }
            elementId = `alert-${id}`
        }

        new Alert({id: elementId, content: message, dismissCallback, ...alertProps});
        if (message.includes('offline')) {
            this._soundManager.playDisconnected();
            this._notificationManager.sendStatusNotification(message, '', 'sleep');
        } else if (message.includes('online')) {
            this._soundManager.playConnected();
            this._notificationManager.sendStatusNotification(message, '', 'walk');
        }
    }

    _receivedToolList({'perm level': permLevel, data}) {
        if (permLevel === 'admin') {
            AdminTools.instance(this).setTools(data);
        } else if (permLevel === 'mod') {
            ModTools.instance(this).setTools(data);
        }
    }

    _receivedToolData(toolData) {
        const permLevel = toolData['tool info']['perm level'];
        if (permLevel === 'admin') {
            AdminTools.instance(this).populateTool(toolData);
        } else if (permLevel === 'mod') {
            ModTools.instance(this).populateTool(toolData);
        }
    }

    _receivedToolConfirm({'perm level': permLevel, message}) {
        if (permLevel === 'admin') {
            AdminTools.instance(this).toolConfirm(message);
        } else if (permLevel === 'mod') {
            ModTools.instance(this).toolConfirm(message);
        }
    }

    _attemptReconnect(numRetries = INITIAL_RETRIES) {
        // try 3 times initially, 5 seconds apart. this'll catch a server reboot. then try every minute until we get in
        const firstPhaseDelay = 5 * 1000;
        const secondPhaseDelay = 60 * 1000;

        // if not already present, show the disconnected alert
        if (!this._disconnectedAlert) {
            this._disconnectedAlert = new Alert({content: 'Connection lost!!', type: 'permanent'});
        }

        // remove any lingering reconnect alert
        if (this._reconnectAlert) {
            this._reconnectAlert.remove();
            this._reconnectAlert = null;
        }
        // clear existing timeout
        window.clearTimeout(this._reconnectTimeout);
        // define timeout callback
        const reconnect = () => {
            this._reconnectCount++;
            new Alert({content: 'Attempting to reconnect to the server...'});
            this.connect();
        };

        // do the automatic reconnection attempts
        if (this._reconnectCount < numRetries) {
            this._reconnectTimeout = window.setTimeout(
                reconnect,
                // first attempt is immediate, all subsequent are delayed
                this._reconnectCount === 0 ? 0 : firstPhaseDelay
            );
        } else if (!this._reconnectAlert) {
            window.setTimeout(() => {
                this._reconnectAlert = new Alert({
                    content: 'I\'ll try to reconnect you soon',
                    type: 'dismiss',
                    dismissText: 'No, try right now',
                    dismissCallback: reconnect
                });
            }, firstPhaseDelay)
            this._reconnectTimeout = window.setTimeout(() => {
                this._reconnectAlert.remove();  // clear the force retry alert
                reconnect();
            }, secondPhaseDelay);
        }
    }
}
