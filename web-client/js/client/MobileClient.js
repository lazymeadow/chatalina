import { BestEvarChatClient } from './BestEvarChatClient'
import { RoomManager } from '../rooms'
import { MainMenu } from '../components'
import { _parseEmojis } from '../lib'
import { Settings } from '../util'

export class MobileClient extends BestEvarChatClient {
    constructor() {
        super()
        this._mainMenu = new MainMenu(this, {
            settings: true,
            bugReport: true,
            featureRequest: true,
            about: true,
        })
        this._mainMenu.init()
        this._roomManager = new RoomManager(this, this._messageLog, this._soundManager)
    }

    _getTitle() {
        $('#current-log-name').text(Settings.activeLogType === 'thread'
            ? this._userManager.getActiveThreadName()
            : this._roomManager.getActiveRoomName())

        _parseEmojis()

        return process.env.CHAT_TITLE || 'Chat'
    }
}
