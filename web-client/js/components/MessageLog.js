import imagesLoaded from 'imagesloaded';

imagesLoaded.makeJQueryPlugin($);

import {LoggingClass} from "../util";
import {_formatTime, _parseEmojis} from "../lib";
import {Settings} from "../util";

export class MessageLog extends LoggingClass {
    constructor() {
        super();
        this._logElement = $('#log');
    }

    /**
     * Print a chat message to the log.
     * <div class="chat-message">
     *     <div class="timestamp">[MM/DD/YYYY HH:MM]</div>
     *     <div class="message">
     *         <span class="username">username</span>
     *         <span>message body</span>
     *     </div>
     * </div>
     *
     * @param time message time
     * @param username sender
     * @param color message color
     * @param message message body
     * @param image_url image url
     * @param image_src_url image src url
     * @param track_link gorilla groove track link
     * @param nsfw_flag image is nsfw
     */
    printMessage({time, username, color, message, 'image url': image_url, 'image src url': image_src_url, 'nsfw flag': nsfw_flag, 'track link': track_link}) {
        let messageContainer = $('<div>').addClass('chat-message');
        // set the message color
        if (color)
            messageContainer.css('color', color);
        if (username === 'Server') {
            messageContainer.addClass('server-message');
        }
        if (username === 'Client') {
            messageContainer.addClass('client-message');
        }

        // add the timestamp
        messageContainer.append($('<span>').addClass('timestamp').text(_formatTime(time)));

        let messageContent = message;
        // if the message is an image, create the <img> as the  message body
        if (!!image_url) {
            let imageElement = $('<a>').prop('href', image_url)
                .prop('target', '_blank')
                .prop('rel', 'noreferrer noopener')
                .append($('<img>').prop('src', image_src_url).prop('alt', 'some image, idk'));
            let hideImage = Settings.hideImages || nsfw_flag;
            hideImage ? imageElement.hide() : imageElement.show();
            messageContent = $('<div>').addClass('image-wrapper')
                .append($('<span>').text((hideImage ? 'show' : 'hide') + ' image' + (nsfw_flag ? ' -- NSFW!' : ''))
                    .click(function (event) {
                        let image_element = $(event.target).next();
                        image_element.toggle();
                        $(event.target).text((image_element.is(':visible') ? 'hide' : 'show') + ' image ' + (nsfw_flag ? '-- NSFW!' : ''))
                    }))
                .append(imageElement);
        } else if (!!track_link) {
            let iframeElement = $('<iframe>').prop('src', track_link + '&mode=fancy');
            messageContent = $('<div>').addClass('track-wrapper')
                .append($('<a>').text('Shared from Gorilla Groove')
                    .prop('href', track_link)
                    .prop('target', '_blank')
                    .prop('rel', 'noreferrer noopener'))
                .append(iframeElement);
        }

        // add the message body
        let messageElement = $('<div>').addClass('message')
            .append($('<span>').addClass('username').text(username + ': '))
            .append($('<span>').html(messageContent));

        this._logElement.append(messageContainer.append(messageElement));

        messageContainer.imagesLoaded(() => {
            if (autoScroll) {
                this._logElement.scrollTop(this._logElement[0].scrollHeight);
            }
        });

        _parseEmojis(messageContainer[0]);
        // super.debug('Added message to log.');
    }

    /**
     * Prints an array of messages to the log.
     *
     * @param messages an array of messages to print
     * @param emptyMessage string to display when there are no messages
     */
    printMessages(messages, emptyMessage = '') {
        super.debug('Printing message log...');
        this.clear();
        if (messages.size > 0) {
            messages.forEach((item) => this.printMessage(item));
        }
        else {
            this._logElement.html(`<div class="chat-message"><em>${emptyMessage}</em></div>`);
        }
        _parseEmojis();
        super.debug('Message log printed.');
    }

    /**
     * Empty the message log.
     */
    clear() {
        this._logElement.empty();
    }
}