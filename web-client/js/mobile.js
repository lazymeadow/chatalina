/**
 * CHATALINA 4.0.0 MOBILE CLIENT
 * @author Audrey Wiltsie
 */

import Cookies from 'js-cookie';
import {MobileClient} from "./client";
import {postClientInit, preClientInit} from "./lib";

if (!Cookies.get('id')) {
    location.reload();
}

$(() => {
    const overlay = $('.overlay');

    const hideMenu = () => {
        const popoutMenu = $('#main-menu + .popout-menu');
        popoutMenu.animate({right: '-80%'}, {
            duration: 500,
            done: () => {
                popoutMenu.hide();
                overlay.hide();
            }
        });
    };

    // overlay dismisses on click and slides menu out
    overlay.click(hideMenu);


    $('#main-menu + .popout-menu').children().click(hideMenu);

    // slide in main menu on click and show overlay
    $('#main-menu').click(event => {
        event.stopPropagation();
        overlay.show();
        $('#main-menu + .popout-menu').show().animate({right: '0'}, {duration: 500});
    });

    preClientInit();
    postClientInit(new MobileClient());
});
