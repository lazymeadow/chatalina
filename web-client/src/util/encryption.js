import {useEffect, useState} from 'react'


class EncryptionManager {
	initialized = false
	enc = new TextEncoder()
	dec = new TextDecoder()
	curveDefinition = {name: 'ECDH', namedCurve: 'P-256'}

	serverKey = null

	async init(callback) {
		this.keyPair = await window.crypto.subtle.generateKey(
			this.curveDefinition,
			true,
			['deriveKey', 'deriveBits']
		)

		const publicKeyRaw = await window.crypto.subtle.exportKey('spki', this.keyPair.publicKey)
		this.publicKey = arrBuffToStr(publicKeyRaw)

		this.initialized = true
		callback()
	}

	getPublicKey = () => this.publicKey

	async setServerKey(publicKey) {
		this.serverKey = await window.crypto.subtle.importKey(
			'spki',
			strToArr(publicKey),
			this.curveDefinition,
			false,  // no reason to export the server's pubkey
			[]  // MUST be empty when importing pub key :/
		)
		this.derivedKey = await window.crypto.subtle.deriveKey(
			{name: 'ECDH', public: this.serverKey},
			this.keyPair.privateKey,
			{name: 'AES-CBC', length: 256},
			true,
			['encrypt', 'decrypt']
		)
	}

	async encrypt(dataObject) {
		const iv = crypto.getRandomValues(new Uint8ClampedArray(16))
		const encryptedMessage = await crypto.subtle.encrypt(
			{
				name: 'AES-CBC',
				iv
			},
			this.derivedKey,
			this.enc.encode(JSON.stringify(dataObject))
		)

		return {
			iv: base64EncArr(iv),
			// must turn the array buffer into an array to be able to encode it
			content: arrBuffToStr(encryptedMessage)
		}
	}

	async decrypt({content, iv}) {
		const decrypted = await crypto.subtle.decrypt(
			{
				name: 'AES-CBC',
				iv: strToArr(iv)
			},
			this.derivedKey,
			strToArr(content)
		)

		return JSON.parse(this.dec.decode(decrypted))
	}
}

const encryptionManager = new EncryptionManager()

export function useEncryption() {
	const [initialized, setInitialized] = useState(false)

	useEffect(() => {
		encryptionManager.init(() => setInitialized(true))
	}, [])

	return {
		initialized, encryption: encryptionManager
	}
}


function strToArr(str) {
	let raw = atob(str)
	let array = new Uint8Array(new ArrayBuffer(raw.length))
	for (let i = 0; i < raw.length; i++) {
		array[i] = raw.charCodeAt(i)
	}
	return array
}

function arrBuffToStr(arrBuff) {
	return btoa(String.fromCharCode.apply(null, new Uint8Array(arrBuff)))
}

/* Base64 string to array encoding */

function uint6ToB64(nUint6) {

	return nUint6 < 26 ?
		nUint6 + 65
		: nUint6 < 52 ?
			nUint6 + 71
			: nUint6 < 62 ?
				nUint6 - 4
				: nUint6 === 62 ?
					43
					: nUint6 === 63 ?
						47
						:
						65

}

function base64EncArr(aBytes) {
	var nMod3 = 2, sB64Enc = ''

	for (var nLen = aBytes.length, nUint24 = 0, nIdx = 0; nIdx < nLen; nIdx++) {
		nMod3 = nIdx % 3
		if (nIdx > 0 && (
			nIdx * 4 / 3
		) % 76 === 0) {
			sB64Enc += '\r\n'
		}
		nUint24 |= aBytes[nIdx] << (
			(
				16 >>> nMod3
			) & 24
		)
		if (nMod3 === 2 || aBytes.length - nIdx === 1) {
			sB64Enc += String.fromCharCode(
				uint6ToB64((
					nUint24 >>> 18
				) & 63),
				uint6ToB64((
					nUint24 >>> 12
				) & 63),
				uint6ToB64((
					nUint24 >>> 6
				) & 63),
				uint6ToB64(nUint24 & 63)
			)
			nUint24 = 0
		}
	}

	return sB64Enc.substr(0, sB64Enc.length - 2 + nMod3) + (
		nMod3 === 2 ? '' : nMod3 === 1 ? '=' : '=='
	)

}
