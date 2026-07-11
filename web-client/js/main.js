/**
 * CHATALINA 4.0.0 WEB CLIENT
 * @author Audrey Wiltsie
 */

import { DesktopClient } from './client'
import { postClientInit, preClientInit } from './lib'
import { initializeKeycloak } from './auth/keycloak'


$(() => {
    const overlay = $('.overlay')

    // overlay dismisses and hides menu on click
    overlay.click(() => {
        overlay.hide()
        $('.popout-menu').hide()
    })

    // open main menu on click and show overlay
    $('#main-menu').click(event => {
        event.stopPropagation()
        overlay.show()
        $('.popout-menu').toggle()
    })

    initializeKeycloak((authenticated) => preClientInit(authenticated).then(() => postClientInit(new DesktopClient())))
})
