// import window from "window";

export default class EncryptionManager {
	initialized = false
	enc = new TextEncoder()
	dec = new TextDecoder()
	curveDefinition = {name: "ECDH", namedCurve: "P-256"}

	serverKey = null

	async init() {
		this.keyPair = await window.crypto.subtle.generateKey(
			this.curveDefinition,
			true,
			["deriveKey", "deriveBits"]
		)

		const publicKeyRaw = await window.crypto.subtle.exportKey("spki", this.keyPair.publicKey)
		this.publicKey = btoa(String.fromCharCode.apply(null, new Uint8Array(publicKeyRaw)))

		this.initialized = true
	}

	constructor() {
		this.init()
	}



	async setServerKey(publicKey) {
		this.serverKey = await window.crypto.subtle.importKey(
			"spki",
			strToArr(publicKey),
			this.curveDefinition,
			false,  // no reason to export the server's pubkey
			[]  // MUST be empty when importing pub key :/
		)
		this.derivedKey = await window.crypto.subtle.deriveKey(
			{ name: "ECDH", public: this.serverKey },
			this.keyPair.privateKey,
			{ name: "AES-CBC", length: 256 },
			true,
			["encrypt", "decrypt"]
		)
	}
}

function strToArr(str) {
	let raw = atob(str)
	let array = new Uint8Array(new ArrayBuffer(raw.length))
	for (let i=0; i<raw.length; i++) {
		array[i] = raw.charCodeAt(i)
	}
	return array
}