export const Loading = ({message}) => {
	return (
		<div>
			<h1>Loading...</h1>
			<p style={{fontStyle: 'italic', whiteSpace: "pre-line"}}>{message}</p>
		</div>
	)
}