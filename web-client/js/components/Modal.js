import {LoggingClass} from "../util";

export class Modal extends LoggingClass {
    constructor({
                    title,
                    message,
                    content,
                    buttonText,
                    buttonClickHandler,
                    showCancel = true,
                    cancelText = 'Cancel',
                    onCancel = () => false,
                    form = false,
                    id = 'modal'
                }) {
        super();
        this.debug(`Creating modal "${title}"`);
        const overlay = $('.overlay');

        this.modal = $('<div>', {id});
        let messageDiv = $('<div>', {id: `${id}-message`}).addClass('message').text(message);
        this.modal.addClass('modal').addClass(form ? 'form' : '')
            .attr('role', 'dialog')
            .attr('aria-labelledby', `${id}-title`)
            .attr('aria-describedby', `${id}-message`)
            .click(event => event.stopPropagation())
            .append($('<h1>', {id: `${id}-title`}).text(title))
            .append($('<div>')
                .append(messageDiv)
                .append(content)
                .append($('<div>').addClass('form-element')
                    .append(showCancel ? $('<button>').addClass('secondary').text(cancelText)
                        .click(event => {
                            event.stopPropagation();
                            this.modal.remove();
                            if (overlay.is(':empty')) {
                                overlay.hide();
                            }
                            if (onCancel) {
                                onCancel();
                            }
                        }) : null)
                    .append(form ? $('<div>').addClass('flex-spacer') : null)
                    .append($('<button>').text(buttonText).click(event => {
                        event.stopPropagation();
                        const error = buttonClickHandler();
                        if (error) {
                            messageDiv.addClass('error').text(error);
                        } else {
                            this.modal.remove();
                            if (overlay.is(':empty')) {
                                overlay.hide();
                            }
                        }
                    }))));

        overlay.append(this.modal).one('click', () => {
            this.modal.remove();
            if (onCancel) {
                onCancel();
            }
        }).show();
    }
}