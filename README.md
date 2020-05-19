# bigboard

Monitoring station with web interface & cron scheduling.

## Prerequisites

You will need Java.

## Installation

Just download the latest .jar and place it somewhere.

## Configuration

You will need to configure 3 environment variables.  Note that there are **two underscores** in each of these.

- `BIGBOARD__REPORTERS`: This tells bigboard where to find your reporters
- `BIGBOARD__STORIES`: This is where stories will be generated
- `BIGBOARD__DATA`: A location for random data which your reporters generate.

Within the jar are a couple of editable resource files you may want to change

- `runners.edn`: A map of file extensions (eg `.py`), and the commands required to run them
- `config.edn`: You can edit the port (default: 10000) & database url (default: installation directory) here.

## Nomenclature

Bigboard borrows 3 words from the newsroom

- Reporters: scripts which generate `.csv` or `.json` files
- Stories: `.csv` or `.json` files which describe some data being reported
- Schedules: A reporter and story together with a cron schedule and a name.

## Stories

Stories can have 3 different extensions.  If your story is, for example, `out.csv`, then your reporter can generate

- `out.csv`: everything is good - no problems to report here.
- `out.csv.prob`: Indicates that your reporter found some issue that needs to be looked into
- `out.csv.err`: Indicates that your reporter returned an exit code above zero.  Probably it crashed, and you want it to leave some troubleshooting information behind.

If your story crashes and it hasn't generated a `.err` file, the frontend will display an exit code for you.

### Coloring Table Rows

If you have a csv story and want to highlight certain rows, do this:

1. Add a column 'bigboard-story-class'
2. Give it one of the following values
  - csv-success
  - csv-danger
  - csv-warning
  - csv-info

## Running

To start a web server for the application, run

    java -jar bigboard-<ver>-standalone.jar

## Example usage

TODO

## Troubleshooting

### bigboard isn't executing my perfectly written script

Probably you need to edit `runners.edn` inside of the jar file.  Bigboard may not be aware of how to run your script.

### Something is up with my cron schedule

Please open an issue [here](https://github.com/crinklywrappr/gooff/issues)
