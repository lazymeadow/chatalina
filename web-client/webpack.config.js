const webpack = require('webpack')
const path = require('path')
const CopyPlugin = require('copy-webpack-plugin')
const MiniCssExtractPlugin = require('mini-css-extract-plugin')

const devMode = process.env.NODE_ENV !== 'production'

const config = [
	{
		entry: {
			main: ['./js/main.js', './less/chat.less'],
			mobile: ['./js/mobile.js', './less/mobile.less'],
			small: ['./less/login.less']
		},
		output: {
			clean: true,
			filename: '[name].js'
		},
		module: {
			rules: [
				{
					test: /\.js$/,
					use: 'babel-loader',
					exclude: /node_modules/,
					type: 'javascript/auto'
				},
				{
					test: /\.less$/,
					use: [
						MiniCssExtractPlugin.loader,
						'css-loader',
						'less-loader'
					]
				},
				{
					test: /.(ttf|otf|eot|svg|woff(2)?)$/,
					type: 'asset/resource',
					generator: {
						filename: 'fonts/[hash][ext]'
					}
				}
			]
		},
		plugins: [
			new webpack.ProvidePlugin({$: 'jquery', jQuery: 'jquery'}),
			new webpack.EnvironmentPlugin({'BEC_SERVER': 'localhost:6969'}),
			new MiniCssExtractPlugin({filename: '[name].css'}),
			new CopyPlugin({patterns: [{from: 'files/*', to: '[name][ext]'}]})
		]
	},
]

module.exports = config
