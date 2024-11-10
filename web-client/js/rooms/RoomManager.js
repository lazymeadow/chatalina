import {LoggingClass, Settings} from "../util";
import {Room} from "./Room";
import {_focusChatBar, _parseEmojis} from '../lib';

export class RoomManager extends LoggingClass {
    constructor(chatClient, messageLog, soundManager) {
        super();
        this._chatClient = chatClient;
        this._messageLog = messageLog;
        this._soundManager = soundManager;
        this._roomDataMap = new Map();
        this._roomListElement = $('#rooms');

        this.allowRoomEdits = false;
    }


    // Public functions

    /**
     * Add rooms to the list. If the provided list is all rooms that are present in the chat, the list will be rebuilt.
     * @param rooms array of room data objects
     * @param allRooms flag to rebuild list
     * @param clearRoomLog flag to clear room message log on update
     */
    addRooms(rooms, allRooms = false, clearRoomLog = false) {
        super.debug('Updating rooms...');
        // clear the list if necessary
        if (allRooms) {
            this._roomListElement.empty();
            this._roomDataMap.clear();
        }
        // add the room data to the list
        rooms.forEach((room) => this._addRoom(room, clearRoomLog));
        if (Settings.activeLogType === 'room') {
            // select the active room. if the active room is not present, try the first available.
            const knownRoom = this._roomDataMap.has(Settings.activeLogId)
            if (!Settings.activeLogId || !knownRoom) {
                Settings.activeLogId = this._roomDataMap.keys().next().value;
            }
            if (this._roomDataMap.has(Settings.activeLogId)) {
                this._roomDataMap.get(Settings.activeLogId).selectThisRoom();
            }
        }
        super.debug('Rooms updated.');
        _parseEmojis(this._roomListElement[0]);
    }

    /**
     * Add a message to room history. If the roomId is not provided, it will be treated as a global message and added
     * to all rooms.
     * @param roomId the id of the room
     * @param messageData
     */
    addMessage(messageData, roomId = null) {
        if (roomId === null) {
            let currentRoomMessageCount = 0;
            this._roomDataMap.forEach((room, roomId) => {
                const messageCount = room.addMessage(messageData, false);
                if (Settings.activeLogType === 'room' && Settings.activeLogId === roomId) {
                    currentRoomMessageCount = messageCount;
                }
            });
            if (Settings.activeLogType === 'room') {
                if (currentRoomMessageCount <= 1) {
                    this._messageLog.clear();
                }
                this._messageLog.printMessage(messageData);
            }
        } else {
            const roomMessageCount = this._roomDataMap.get(roomId).addMessage(messageData);
            if (Settings.activeLogType === 'room' && Settings.activeLogId === roomId) {
                if (roomMessageCount <= 1) {
                    this._messageLog.clear();
                }
                this._messageLog.printMessage(messageData);
            }
            if (messageData.username === Settings.username) {
                this._soundManager.playSent();
            } else if (messageData.username !== 'Server') {
                this._soundManager.playReceived();
            }
        }
    }

    notifyClient(metadata) {
        this._chatClient.sendMessageNotification(`New Message in ${metadata.name}`, `from: ${metadata.user}`, 'tied');
    }

    /**
     * Save the new roomId in the client settings, then repopulate the message log.
     * @param roomId
     */
    setActiveRoom(roomId) {
        Settings.activeLogType = 'room';
        Settings.activeLogId = roomId;
        let room = this._roomDataMap.get(roomId);
        this._messageLog.printMessages(room.messageHistory, 'There are no messages in this room. You should say something!');
        this._chatClient.sendTyping();
        this._chatClient.setWindowTitle();
        this._chatClient.updateUserList();
        _focusChatBar();
        super.debug(`Active room set to ${roomId}.`);
    }

    getActiveRoomName() {
        return this._roomDataMap.get(Settings.activeLogId)?.name || '-';
    }

    // Private functions

    _addRoom(roomData, clearRoomLog) {
        if (this._roomDataMap.has(roomData.id)) {
            const room = this._roomDataMap.get(roomData.id);
            if (clearRoomLog) {
                room.resetHistory();
            } else {
                $.merge(room.messageHistory, roomData.history);
            }
            room.memberList = new Set(roomData.members);
            room.owner = roomData.owner;
            room.refreshRoomElement();
            this.debug(`Room '${roomData.name}' updated.`);
        } else {
            const newRoom = new Room(roomData, this);
            this._roomDataMap.set(newRoom.id, newRoom);
            this._roomListElement.append(newRoom.template);
            this.debug(`Room '${roomData.name}' added.`);
        }
    }

    _getAllUsers() {
        return this._chatClient._userManager._userDataMap.keys()
    }
}
