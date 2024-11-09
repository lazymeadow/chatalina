const webpack = require('webpack')
const path = require('path')
const CopyPlugin = require('copy-webpack-plugin')
const MiniCssExtractPlugin = require('mini-css-extract-plugin')
const Dotenv = require('dotenv-webpack')

module.exports = (env) => (
    {
        devtool: 'eval-source-map',
        entry: {
            main: ['./js/main.js', './less/chat.less'],
            mobile: ['./js/mobile.js', './less/mobile.less'],
            small: ['./less/login.less'],
            login: ['./js/pages/login.js'],
            register: ['./js/pages/register.js'],
            reactivate: ['./js/pages/reactivate.js'],
            'forgot-password': ['./js/pages/forgot-password.js'],
            'reset-password': ['./js/pages/reset-password.js']
        },
        output: {
            clean: true,
            filename: '[name].js',
            cssFilename: '[name].css'
        },
        module: {
            rules: [
                {
                    test: /\.js$/,
                    use: {
                        loader: 'babel-loader',
                        options: {
                            presets: ['@babel/preset-env']
                        }
                    },
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
            new Dotenv({
                path: env.production ? '.env.production' : '.env'
            }),
            new MiniCssExtractPlugin(),
            new CopyPlugin({patterns: [{context: 'files/', from: '**', to: '[path][name][ext]'}]})
        ]
    }
)
