$(function () {
    $('form').on('submit', async function (event) {
        event.preventDefault();

        const data = Object.fromEntries(new FormData(this));
        const response = await fetch("/register", {
            method: "POST",
            body: JSON.stringify(data),
            headers: {"Content-Type": "application/json"}
        });
        if (!response.ok) {
            const message = await response.text()
            const messageElement = $('#message');
            messageElement.addClass('error')
            messageElement.text(message || 'There was a problem registering.')
        } else {
            const query = new URLSearchParams({
                message: "All signed up! I hope you remember that password. Time to log in!",
                parasite: data.parasite
            })
            window.location = `/login?${query.toString()}`
        }
    });
});
