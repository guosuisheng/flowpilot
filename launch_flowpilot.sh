set -e
source ./.env

export WIDE_ROAD_CAMERA_SOURCE="selfdrive/assets/tmp" # no affect on android
export ROAD_CAMERA_SOURCE="selfdrive/assets/tmp" # no affect on android
export USE_GPU="0" # no affect on android, gpu always used on android
export PASSIVE="0"
export NOSENSOR="1"
#export MSGQ="1"
#export USE_PARAMS_NATIVE="1"
export ZMQ_MESSAGING_PROTOCOL="TCP" # TCP, INTER_PROCESS, SHARED_MEMORY

#export DISCOVERABLE_PUBLISHERS="1" # if enabled, other devices on same network can access sup/pub data.
#export DEVICE_ADDR="127.0.0.1" # connect to external device running flowpilot over same network. useful for livestreaming.

export SIMULATION="1"
export FINGERPRINT="HYUNDAI KONA ELECTRIC 2019"

## android specific ##
export USE_SNPE="1" # only works for snapdragon devices.


if ! command -v tmux &> /dev/null
then
    echo "tmux could not be found, installing.."
    sudo apt-get update
    sudo apt-get install tmux
    echo "set -g mouse on" >> ~/.tmux.conf # enable mouse scrolling in tmux
    echo "set -g remain-on-exit on" >> ~/.tmux.conf # retain tmux session on ctrl + c
fi

# make sure we kill any existing terminal sessions
tmux kill-window -t 0
tmux kill-window -t 1
tmux kill-window -t 2

# start a tmux pane
tmux new-session -d -s "flowpilot" "scons && flowinit"
tmux attach -t flowpilot

while true; do sleep 1; done
