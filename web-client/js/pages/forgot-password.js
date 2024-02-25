$(function () {
    $('form').on('submit', async function (event) {
        event.preventDefault();

        const data = Object.fromEntries(new FormData(this));
        const response = await fetch("/forgot-password", {
            method: "POST",
            body: JSON.stringify(data),
            headers: {"Content-Type": "application/json"}
        });
        if (!response.ok) {
            const message = await response.text()
            const messageElement = $('#message');
            messageElement.addClass('error')
            messageElement.text(message || 'There was a problem requesting a password reset.')
        } else {
            const query = new URLSearchParams({
                message: "A password reset link has been sent to your email. Check your spam folder!",
                parasite: data.parasite
            })
            window.location = `/login?${query.toString()}`
        }
    });
});
