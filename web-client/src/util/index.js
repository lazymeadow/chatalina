export const sortBy = (array, prop) => array.sort((a, b) => {
	if (a[prop] > b[prop]) {
		return 1
	} else if (a[prop] < b[prop]) {
		return -1
	} else {
		return 0
	}
})