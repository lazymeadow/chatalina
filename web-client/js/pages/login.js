$(function () {
    const last_login = localStorage.getItem('last_login');
    if (last_login) {
        $('#parasite').val(last_login);
        $('#remember').prop('checked', true);
    }

    $('form').on('submit', async function (event) {
        event.preventDefault();

        const data = Object.fromEntries(new FormData(this));

        if ($('#remember').is(':checked')) {
            localStorage.setItem('last_login', data.parasite);
        } else {
            localStorage.removeItem('last_login');
        }

        const response = await fetch("/login", {
            method: "POST",
            body: JSON.stringify(data),
            headers: {"Content-Type": "application/json"}
        });
        if (!response.ok) {
            let message
            if (response.status === 401) {
                message = "Invalid username or password."
            } else {
                message = await response.text()
            }
            const messageElement = $('#message');
            messageElement.addClass('error')
            messageElement.text(message || 'There was a problem logging in.')
        } else {
            window.location = '/'
        }
    });
});
