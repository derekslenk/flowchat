# Building the front end
cd ui
yarn
yarn build
cp -R dist/ ../service/src/main/resources
cd ..

# Building the back end
cd service
sh install.sh
