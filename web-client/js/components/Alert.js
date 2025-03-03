import {LoggingClass} from "../util";
import {_parseEmojis} from "../lib";


export class Alert extends LoggingClass {
    constructor({id, content, type = 'fade', actionText, actionCallback, dismissText = 'Dismiss', dismissCallback}) {
        super();
        const existingElement = $(`#${id}`)
        if (!!id && existingElement.length) {
            this.debug(`Alert with id '${id}' already exists in DOM - replacing`);
            existingElement.remove();
        }

        this.debug('Creating alert');

        // create hidden alert
        this.alert = $('<div>').html(content).hide();
        if (!!id) this.alert.attr("id", id);

        this.alertsBox = $('#alerts');

        if (type === 'fade') {
            this.alert.attr('role', 'status');
            // after timeout, slideUp alert. if empty, slide up box.
            window.setTimeout(() => {
                this._fade();
            }, 3500);
        } else if (type === 'dismiss') {
            this.alert.attr('role', 'alert').append($('<div>').addClass('alert-actions')
                .append(this._dismissElement(dismissText, dismissCallback)));
        } else if (type === 'actionable') {
            this.alert.attr('role', 'alert').append($('<div>').addClass('alert-actions')
                .append(this._dismissElement(dismissText, dismissCallback))
                .append($('<span>').text(actionText).click(() => {
                    actionCallback();
                    this._fade();
                })));
        }

        // append hidden alert
        this.alertsBox.prepend(this.alert);
        // process emojis in the alerts box
        _parseEmojis(this.alertsBox[0]);

        // slideDown alert
        this.alert.slideDown(500);
        // if previously empty, slideDown alerts box
        if (this.alert.is(':last-child')) {
            this.alertsBox.slideDown(500);
        }
    }

    remove() {
        this._fade();
    }

    _fade() {
        this.alert.slideUp(500, () => {
            this.alert.remove();
            if (this.alertsBox.is(':empty')) {
                this.alertsBox.slideUp(500);
            }
        });
    }

    _dismissElement(dismissText, dismissCallback) {
        return $('<span>').text(dismissText).click(() => {
            if (dismissCallback) {
                dismissCallback();
            }
            this._fade();
        });
    }
}