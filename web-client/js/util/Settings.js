import Cookies from 'js-cookie';

export class Settings {
    static #currentLogId = null;
    static #currentLogType = null

    static get allowedFactions() {
        return {
            'first-order': 'First Order',
            'first-order-alt': 'First Order (Alternate)',
            'empire': 'Galactic Empire',
            'galactic-republic': 'Galactic Republic',
            'galactic-senate': 'Galactic Senate',
            'jedi-order': 'Jedi Order',
            'mandalorian': 'Mandalorian',
            'old-republic': 'Old Republic',
            'rebel': 'Rebel Alliance',
            'sith': 'Sith',
            'trade-federation': 'Trade Federation',
        };
    }

    static get themes() {
        return {
            'classic-teal': 'Classic Teal',
            'newfangled-red': 'Newfangled Red',
        }
    }

    static init() {
        // set server values
        Settings.username = decodeURIComponent(Cookies.get('displayName').replace(/\+/g, " "));
        Settings.faction = Cookies.get('faction');
        Settings.color = Cookies.get('color');
        Settings.soundSet = Cookies.get('soundSet');
        Settings.email = Cookies.get('email');
        Settings.permission = Cookies.get('permission');

        // set value overrides
        Settings.volume = Settings.volume || Cookies.get('volume');
        $('body')[0].style.fontSize = `${Settings.fontSize}px`;
        Settings.theme = Cookies.get('theme');
    }

    static get userId() {
        return Cookies.get('id');
    }

    static get username() {
        return localStorage.getItem(`${Settings.userId}.username`);
    }

    static set username(username) {
        localStorage.setItem(`${Settings.userId}.username`, username);
    }

    static get activeLogId() {
        return this.#currentLogId || localStorage.getItem(`${Settings.userId}.activeLogId`) || '0';
    }

    static set activeLogId(roomOrThreadId) {
        this.#currentLogId = roomOrThreadId;
        localStorage.setItem(`${Settings.userId}.activeLogId`, roomOrThreadId);
    }

    static get activeLogType() {
        return this.#currentLogType || localStorage.getItem(`${Settings.userId}.activeLogType`) || 'room';
    }

    static set activeLogType(type) {
        if ($.inArray(type, ['room', 'thread']) >= 0) {
            this.#currentLogType = type;
            localStorage.setItem(`${Settings.userId}.activeLogType`, type);
        }
    }

    static get faction() {
        return localStorage.getItem(`${Settings.userId}.faction`);
    }

    static set faction(faction) {
        if ($.inArray(faction, Object.keys(Settings.allowedFactions)) >= 0) {
            localStorage.setItem(`${Settings.userId}.faction`, faction);
        }
    }

    static get theme() {
        return localStorage.getItem(`${Settings.userId}.theme`) || 'classic-teal';
    }

    static set theme(theme) {
        if (Settings.themes.hasOwnProperty(theme)) {
            localStorage.setItem(`${Settings.userId}.theme`, theme);
            $('body').removeClass(Object.keys(Settings.themes).join(" ")).addClass(theme);
        }
    }

    static get color() {
        return localStorage.getItem(`${Settings.userId}.color`);
    }

    static set color(color) {
        localStorage.setItem(`${Settings.userId}.color`, color);
    }

    static get tabTitle() {
        return localStorage.getItem(`${Settings.userId}.tabTitle`) || '';
    }

    static set tabTitle(tabTitle) {
        if (tabTitle) {
            localStorage.setItem(`${Settings.userId}.tabTitle`, tabTitle);
        } else {
            localStorage.removeItem(`${Settings.userId}.tabTitle`);
        }
    }

    static get volume() {
        return localStorage.getItem(`${Settings.userId}.volume`);
    }

    static set volume(volume) {
        localStorage.setItem(`${Settings.userId}.volume`, volume);
    }

    static get muted() {
        const mutedValue = localStorage.getItem(`${Settings.userId}.muted`);
        return mutedValue ? mutedValue === 'true' : false;
    }

    static set muted(muted) {
        localStorage.setItem(`${Settings.userId}.muted`, muted);
    }

    static get soundSet() {
        return localStorage.getItem(`${Settings.userId}.soundSet`);
    }

    static set soundSet(soundSet) {
        localStorage.setItem(`${Settings.userId}.soundSet`, soundSet);
    }

    static get fontSize() {
        return localStorage.getItem(`${Settings.userId}.fontSize`) || 14;
    }

    static set fontSize(fontSize) {
        localStorage.setItem(`${Settings.userId}.fontSize`, fontSize);
    }

    static get hideImages() {
        const storedSetting = localStorage.getItem(`${Settings.userId}.hideImages`);
        return storedSetting === null ? true : storedSetting === 'true';
    }

    static set hideImages(hideImages) {
        localStorage.setItem(`${Settings.userId}.hideImages`, hideImages);
    }

    static get timestamps() {
        return localStorage.getItem(`${Settings.userId}.timestamps`) || 'date_time';
    }

    static set timestamps(timestamps) {
        localStorage.setItem(`${Settings.userId}.timestamps`, timestamps);
    }

    static get notifications() {
        return localStorage.getItem(`${Settings.userId}.notifications`) || 'all';
    }

    static set notifications(notifications) {
        localStorage.setItem(`${Settings.userId}.notifications`, notifications);
    }

    static get email() {
        return localStorage.getItem(`${Settings.userId}.email`) || '';
    }

    static set email(email) {
        localStorage.setItem(`${Settings.userId}.email`, email);
    }

    static get permission() {
        return localStorage.getItem(`${Settings.userId}.permission`) || 'user';
    }

    static set permission(permission) {
        localStorage.setItem(`${Settings.userId}.permission`, permission);
    }

    static userIsModerator() {
        return Settings.permission === 'mod' || Settings.userIsAdmin();
    }

    static userIsAdmin() {
        return Settings.permission === 'admin';
    }
}