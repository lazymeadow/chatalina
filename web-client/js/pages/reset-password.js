$(function () {
    $('form').on('submit', async function (event) {
        event.preventDefault();

        const {parasite, ...data} = Object.fromEntries(new FormData(this));
        const response = await fetch("/reset-password", {
            method: "POST",
            body: JSON.stringify(data),
            headers: {"Content-Type": "application/json"}
        });
        if (!response.ok) {
            const message = await response.text()
            const messageElement = $('#message');
            messageElement.addClass('error')
            messageElement.text(message || 'There was a problem resetting your password.')
        } else {
            const query = new URLSearchParams({
                message: "Password reset successful.",
                parasite: parasite
            })
            window.location = `/login?${query.toString()}`
        }
    });
});
