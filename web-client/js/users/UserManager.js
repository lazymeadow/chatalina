import moment from 'moment';
import {LoggingClass, Settings} from "../util";
import {User} from "./User";
import {_focusChatBar, _parseEmojis} from "../lib";

export class UserManager extends LoggingClass {
    constructor(chatClient, messageLog, soundManager) {
        super();
        this._chatClient = chatClient;
        this._messageLog = messageLog;
        this._soundManager = soundManager;
        this._activeUserListElement = $('#active-user-list');
        this._inactiveUserListElement = $('#inactive-user-list');
        this._userDataMap = new Map();

        this._inactiveUserListElement.hide();
        const inactiveUserToggle = $('#inactive-user-toggle');
        inactiveUserToggle.click(event => {
            event.stopPropagation();
            this._inactiveUserListElement.toggle();
            this._inactiveUserListElement.is(':visible')
                ? inactiveUserToggle.text('Show fewer users')
                : inactiveUserToggle.text('Show more users');
        });
    }

    getUserStatus(userId) {
        if (this._userDataMap.has(userId)) {
            return this._userDataMap.get(userId).status;
        }
    }

    getUserTypingStatus(userId) {
        if (this._userDataMap.has(userId)) {
            return this._userDataMap.get(userId).typing;
        }
    }

    updateUserList(newUsers) {
        if (newUsers === undefined) {
            this._userDataMap.forEach(user => {
                user.updateTypingStatus();
            })
        } else {
            this._activeUserListElement.empty();
            this._inactiveUserListElement.empty();
            newUsers.forEach((userData) => {
                this._addUser(userData);
            });
            _parseEmojis($('#user-list')[0]);
        }
    }

    addPrivateMessageThreads(threads) {
        threads.forEach((thread) => {
            if (this._userDataMap.has(thread['recipient id'])) {
                this._userDataMap.get(thread['recipient id']).addPrivateMessageThread(thread);
            }
        });
        // select the active thread. if the active thread is not present, reset to general room
        if (Settings.activeLogType === 'thread') {
            if (this._userDataMap.has(Settings.activeLogId)) {
                this.setActiveThread(Settings.activeLogId);
            } else {
                this._chatClient.selectGeneralRoom();
            }
        }
    }

    addMessage({'sender id': senderId, 'recipient id': recipientId, ...messageData}) {
        const otherUserId = recipientId === Settings.userId ? senderId : recipientId;
        const isSelfMessage = recipientId === senderId && recipientId === Settings.userId
        const isCurrentLog = (Settings.activeLogType === 'thread')
            && ((isSelfMessage && recipientId === Settings.activeLogId) || (otherUserId === Settings.activeLogId));

        const totalMessages = this._userDataMap.get(otherUserId).addMessage(
            messageData,
            (senderId !== Settings.userId && !isCurrentLog)
        );
        if (isCurrentLog) {
            if (totalMessages <= 1) {
                this._messageLog.clear();
            }
            this._messageLog.printMessage(messageData);
        }
        if (senderId === Settings.username) {
            this._soundManager.playSent();
        } else {
            this._soundManager.playReceived();
        }
    }

    notifyClient(username) {
        this._chatClient.sendMessageNotification('Private message from ' + username, '', 'tied');
    }

    setActiveThread(userId) {
        Settings.activeLogType = 'thread';
        Settings.activeLogId = userId;
        let user = this._userDataMap.get(userId);
        this._messageLog.printMessages(user._threadMessages, user.id === Settings.userId ?
            'You can talk to yourself here!' : `There are no messages here. Anything you say here is just between you and ${user.username}!`);
        this._chatClient.sendTyping();
        this._chatClient.setWindowTitle();
        _focusChatBar();
        this.updateUserList();
        super.debug(`Active thread set to ${user.id}.`);
    }

    getActiveThreadName() {
        return this._userDataMap.get(Settings.activeLogId)?.username || '-';
    }

    _addUser(userData) {
        let user;
        if (this._userDataMap.has(userData['id'])) {
            // if it's already in the map, update it
            user = this._userDataMap.get(userData['id']);
            user.updateUser(userData);
        } else {
            // otherwise, add a new user
            user = new User(userData, this);
            this._userDataMap.set(userData['id'], user);
        }

        // if a user isn't online, they can be in the active list if they've been active in the last week
        if (user.status !== 'offline' || (user.lastActive && moment.unix(user.lastActive).isSameOrAfter(moment().subtract(7, 'days')))) {
            this._activeUserListElement.append(user.template);
        } else {
            this._inactiveUserListElement.append(user.template);
        }
    }
}
