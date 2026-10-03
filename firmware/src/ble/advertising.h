/***************************************************************************//**
 * @file
 * @brief Connectable advertising with the service UUID, board id and name.
 ******************************************************************************/

#ifndef ADVERTISING_H
#define ADVERTISING_H

// Creates the advertising set. Call once after the stack has booted.
void advertising_init(void);

// (Re)starts advertising with the current name, TX power and interval.
// TX power can only change while the radio is idle, so this is also where
// those settings take effect: at boot and after every disconnect.
void advertising_start(void);

#endif // ADVERTISING_H
