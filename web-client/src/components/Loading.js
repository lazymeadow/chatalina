import './Loading.css'


export const Loading = ({message}) => {
	return (
		<div className={'Loading'}>
			<img className={'Loading-spin'} src={'/cat128.png'} alt={'Kitty cat cleaning their butt'} />
			<h1>Loading...</h1>
			<p style={{fontStyle: 'italic', whiteSpace: "pre-line"}}>{message}</p>
		</div>
	)
}