#pragma once

/******************************************************************************/

#include "proton/codec.h"
#include "amqp/AMQPDescribed.h"
#include "amqp/descriptors/AMQPDescriptor.h"

/******************************************************************************/

namespace amqp::internal {

    class FieldDescriptor : public AMQPDescriptor {
        public :
            FieldDescriptor() = delete;
            FieldDescriptor (std::string, int);

            ~FieldDescriptor() final = default;

            std::unique_ptr<AMQPDescribed> build (pn_data_t *) const override;

            void read (
                pn_data_t *,
                std::stringstream &,
                const AutoIndent &) const override;
    };

}

/******************************************************************************/
