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

    initializeKeycloak((authenticated) => preClientInit(authenticated).then(() => postClientInit(new DesktopClient())))
})
